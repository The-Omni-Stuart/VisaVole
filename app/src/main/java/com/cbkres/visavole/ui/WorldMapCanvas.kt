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
import androidx.compose.ui.platform.LocalDensity
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
// High enough for tiny island states (MT, CY, LU, MC…) to become genuinely inspectable without
// entering a regime where the simplified coastline looks obviously faceted or the screen is mostly
// empty ocean.
private const val MAX_ZOOM = 80f
// The minimum zoom is a width-fit view with a small buffer, not an exact full-geometry fit.
private const val MIN_ZOOM_BUFFER = 1.04f
// Slightly east of the geometric centre so the default world framing feels evenly divided.
private const val DEFAULT_CENTER_X_FRACTION = 0.52f
// Panning may move the map, but at least this fraction of the world (or of the visible viewport)
// must remain on screen.
private const val MIN_VISIBLE_FRACTION = 0.2f

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
    val rings: List<FloatArray>,
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
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val topInsetPx = with(density) { topInset.toPx() }
    var controlsH by remember { mutableStateOf(0.dp) }
    // When a country is selected, keep the tap-to-zoom target above the floating zoom controls so
    // the country is not visually “inside” the control box.
    val controlsBottomInsetPx = if (selected != null) {
        with(density) { (controlsH + controlsBottomPadding + 12.dp).toPx() }
    } else {
        0f
    }
    val shapes: List<IsoShape> = remember(geometry) {
        geometry.countries.map { (iso, shape) ->
            val path = Path()
            var area = 0f
            for (ring in shape.rings) {
                for (i in ring.indices step 2) {
                    val x = ring[i]
                    val y = ring[i + 1]
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
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
    var centerWorld by remember {
        mutableStateOf(Offset(geometry.width * DEFAULT_CENTER_X_FRACTION, geometry.height / 2f))
    }

    // The scale at which the world fits the canvas at the minimum zoom (zoom == 1).
    val baseScale = if (canvasSize.width > 0f) baseScaleFor(geometry, canvasSize.width, canvasSize.height) else 1f

    // Smoothly centre and zoom in when a country becomes selected.
    LaunchedEffect(selected, canvasSize, topInsetPx, controlsBottomInsetPx) {
        if (selected == null || canvasSize.width <= 0f) return@LaunchedEffect
        val f = focus[selected] ?: return@LaunchedEffect
        val w = canvasSize.width
        val h = canvasSize.height
        val top = topInsetPx.coerceIn(0f, h)
        val bottom = controlsBottomInsetPx.coerceIn(0f, h - top)
        val visibleH = (h - top - bottom).coerceAtLeast(1f)
        val bs = baseScaleFor(geometry, w, h)
        val targetZoom = (minOf(w * 0.75f / f.bbox.w, visibleH * 0.75f / f.bbox.h) / bs).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // The canvas itself is centred on `centerWorld`; shift the centre so the selected country
        // lands in the middle of the *visible* strip between the search bar and the bottom UI.
        val visibleCenterShift = Offset(0f, (top - bottom) / (2f * bs * targetZoom))
        val targetCenter = clampCenter(f.point - visibleCenterShift, bs * targetZoom, geometry, w, h)
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
                        var prevPointCount = 1
                        while (true) {
                            val ev = awaitPointerEvent()
                            val act = ev.changes.filter { it.pressed }
                            val ds = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                            val w = ds.width
                            val h = ds.height
                            val bs = baseScaleFor(geometry, w, h)
                            when {
                                act.size >= 2 -> {
                                    val p0 = act[0].position
                                    val p1 = act[1].position
                                    val c = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                    val dist = distBetween(p0, p1)
                                    if (prevPointCount < 2) {
                                        last = c
                                        lastDist = dist
                                        moved = true
                                    } else if (!lastDist.isNaN() && lastDist > 0f) {
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
                                    if (prevPointCount >= 2) {
                                        // Re-anchor the surviving finger so the pinch centroid is not
                                        // interpreted as an extra one-finger pan on release.
                                        last = act[0].position
                                        lastDist = Float.NaN
                                    } else {
                                        val p = act[0].position
                                        val d = Offset(p.x - last.x, p.y - last.y)
                                        if (d.length() > viewConfiguration.touchSlop) {
                                            val sc = bs * zoom
                                            centerWorld = clampCenter(centerWorld - Offset(d.x / sc, d.y / sc), sc, geometry, w, h)
                                            moved = true
                                        }
                                        last = p
                                    }
                                }
                                else -> break
                            }
                            prevPointCount = act.size
                            ev.changes.forEach { it.consume() }
                        }
                        if (!moved) {
                            val ds = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                            val sc = baseScaleFor(geometry, ds.width, ds.height) * zoom
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
            val sc = baseScaleFor(geometry, ds.width, ds.height) * zoom
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = controlsBottomPadding)
                .onSizeChanged { controlsH = Dp(it.height / density.density) },
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
                    centerWorld = Offset(geometry.width * DEFAULT_CENTER_X_FRACTION, geometry.height / 2f)
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

/** The canvas scale at `zoom == 1`: a width-fit world view with a small framing buffer. */
private fun baseScaleFor(geometry: WorldMapData, w: Float, h: Float): Float =
    fitScale(geometry, w, h) * MIN_ZOOM_BUFFER

/**
 * Keep at least [MIN_VISIBLE_FRACTION] of the world on screen while panning. When the whole world
 * fits the viewport on an axis, that axis is pinned to the world centre.
 */
private fun clampCenter(center: Offset, scale: Float, geometry: WorldMapData, w: Float, h: Float): Offset {
    val x = clampAxis(center.x, geometry.width.toFloat(), w / scale)
    val y = clampAxis(center.y, geometry.height.toFloat(), h / scale)
    return Offset(x, y)
}

private fun clampAxis(center: Float, extent: Float, visibleExtent: Float): Float {
    if (visibleExtent >= extent) return extent / 2f
    val minVisible = MIN_VISIBLE_FRACTION * minOf(extent, visibleExtent)
    val halfVisible = visibleExtent / 2f
    val min = minVisible - halfVisible
    val max = extent - minVisible + halfVisible
    return if (min <= max) center.coerceIn(min, max) else extent / 2f
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

private fun bboxOf(rings: List<FloatArray>): BBox {
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

private fun pointInAnyRing(rings: List<FloatArray>, x: Float, y: Float): Boolean {
    for (ring in rings) if (pointInRing(ring, x, y)) return true
    return false
}

private fun pointInRing(ring: FloatArray, x: Float, y: Float): Boolean {
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

private fun polygonArea(ring: FloatArray): Float {
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
    return kotlin.math.abs(a) / 2f
}
