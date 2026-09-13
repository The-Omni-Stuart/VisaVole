package com.cbkres.visavole.ui

import android.os.SystemClock
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
// The minimum zoom is an exact world fit so edge islands remain visible in the default view.
private const val MIN_ZOOM_BUFFER = 1f
// Geometric centre, so an exact world fit keeps equal margins on both map edges.
private const val DEFAULT_CENTER_X_FRACTION = 0.5f
// Pan bounds: fully zoomed out the map is locked to the default centre; as you zoom in the
// free-pan range widens so a map edge can reach the centre of the screen (edge islands like Samoa
// stay centreable). Pushing past the bound is rubber-banded and springs back on release.
private const val RUBBER_BAND_FRICTION = 0.45f
private const val RUBBER_BAND_MAX_FRACTION = 0.4f
private const val SPRING_BACK_MS = 240f
// The pan bound reaches the map edges once the visible world is this fraction of the full extent
// (map ~1/0.7 ≈ 1.4x the screen). Generous so that at a moderate zoom (hunting around Europe) the
// map is already fully free — a fling has the whole map to travel and rarely meets a bound, which
// is what used to read as "bumping into a grid." Edge islands stay reachable by panning too.
private const val EDGE_REACH_VISIBLE_RATIO = 0.7f
// One-finger fling: raw (low-passed) screen px/ms passes through [FLING_GAIN] and is capped, so a
// hard flick clearly flings faster than a gentle one. [FLING_MIN_VELOCITY] is set high enough that
// only a genuine fast flick starts a fling — quick short repositioning pans (especially at a
// mid-zoom where you're hunting for a country) must not fling.
private const val FLING_GAIN = 1.2f
private const val FLING_MAX_X = 4.5f
private const val FLING_MAX_Y = 3.0f
private const val FLING_DECAY_MS = 180f
// A fling only starts from a genuine fast flick (release velocity above this, screen px/ms).
private const val FLING_MIN_VELOCITY = 0.8f
// The fling keeps decaying down to this much lower speed before it stops, so it eases into a
// smooth halt (a little extra glide) instead of cutting off abruptly at the trigger speed.
private const val FLING_STOP_VELOCITY = 0.08f
// When a fling reaches a bound it slides along it; this damps the into-the-edge velocity component
// per frame so the turn eases in over a couple of frames instead of snapping (less "grid" feel).
private const val FLING_EDGE_SLIDE_DAMPING = 0.3f
// Pinch zoom inertia: log-scale zoom velocity (per ms) with decay; capped so it glides, not rockets.
private const val ZOOM_FLING_DECAY_MS = 150f
private const val ZOOM_FLING_MIN_VELOCITY = 0.00015f
private const val ZOOM_FLING_MAX_VELOCITY = 0.004f
private const val ZOOM_FLING_VELOCITY_SCALE = 0.003f
// Experimental performance switch: draw only country paths that intersect the visible canvas.
// Set to false to restore the original full-world draw path.
private const val ENABLE_VIEWPORT_CULLING = true

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
    val bbox: BBox,
)

private data class DrawShape(
    val iso: String,
    val path: Path,
    val bbox: BBox,
    val fill: Color,
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

    fun intersectsViewport(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        maxX >= left && minX <= right && maxY >= top && minY <= bottom
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
            IsoShape(iso, shape.rings, path, area, bboxOf(shape.rings))
        }
    }
    val drawShapes: List<DrawShape> = remember(shapes, access, homeCountries, ownVisaCountries) {
        shapes.map { s ->
            val lvl = access[s.iso]?.level
            DrawShape(
                s.iso,
                s.path,
                s.bbox,
                when {
                    s.iso in homeCountries -> HOME
                    lvl == AccessLevel.COVERED && s.iso in ownVisaCountries -> COVERED_OWN
                    lvl == AccessLevel.RESIDENCE -> RESIDENCE_FILL
                    else -> colorFor(lvl)
                },
            )
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
    val pointerScope = rememberCoroutineScope()

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
        val targetCenter = clampCenter(f.point - visibleCenterShift, bs * targetZoom, geometry, w, h, locked = false)
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
                    var flingJob: Job? = null
                    var zoomFlingJob: Job? = null
                    var settleJob: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        flingJob?.cancel()
                        zoomFlingJob?.cancel()
                        settleJob?.cancel()
                        val panSlop = maxOf(8f, viewConfiguration.touchSlop / 2f)
                        val downTime = SystemClock.uptimeMillis()
                        var last = down.position
                        var lastDist = Float.NaN
                        var dragging = false
                        var prevPointCount = 1
                        var grab: Offset? = null
                        var lastMove = down.position
                        var lastMoveTime = downTime
                        var velocity = Offset.Zero
                        var zoomVelocity = 0f
                        var lastPinchTime = downTime
                        var pinchReanchor: Offset? = null
                        var lastLocked = true
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
                                    val now = SystemClock.uptimeMillis()
                                    if (prevPointCount < 2) {
                                        last = c
                                        lastDist = dist
                                        lastPinchTime = now
                                        zoomVelocity = 0f
                                        zoomFlingJob?.cancel()
                                    } else if (!lastDist.isNaN() && lastDist > 0f) {
                                        val (nz, nc) = zoomAround(centerWorld, zoom, bs, c, dist / lastDist, geometry, w, h)
                                        zoom = nz
                                        centerWorld = nc
                                        // Also follow the centroid's movement (two-finger pan).
                                        val d = Offset(c.x - last.x, c.y - last.y)
                                        val sc = bs * zoom
                                        centerWorld = clampCenter(centerWorld - Offset(d.x / sc, d.y / sc), sc, geometry, w, h, locked = false)
                                        val dt = ((now - lastPinchTime).coerceAtLeast(1L)).toFloat()
                                        val instant = kotlin.math.ln(dist / lastDist) / dt
                                        zoomVelocity = zoomVelocity * 0.7f + instant * 0.3f
                                    }
                                    lastDist = dist
                                    last = c
                                    lastPinchTime = now
                                    dragging = true
                                    grab = null
                                    velocity = Offset.Zero
                                    lastLocked = false
                                }
                                act.size == 1 -> {
                                    val p = act[0].position
                                    val now = SystemClock.uptimeMillis()
                                    if (prevPointCount >= 2) {
                                        // Re-anchor the surviving finger so the pinch centroid is not
                                        // interpreted as an extra one-finger pan on release. Keep
                                        // [zoomVelocity] so a pinch with momentum still glides on
                                        // release; [lastPinchTime] stays stale once a real pan starts.
                                        last = p
                                        lastDist = Float.NaN
                                        grab = null
                                        lastMove = p
                                        lastMoveTime = now
                                        velocity = Offset.Zero
                                        pinchReanchor = p
                                    } else if (!dragging) {
                                        // Activate on cumulative distance from the original down point,
                                        // then grab the world point under the finger so subsequent moves
                                        // track absolute finger position rather than accumulated deltas.
                                        if (kotlin.math.hypot(p.x - down.position.x, p.y - down.position.y) > panSlop) {
                                            dragging = true
                                            val sc = bs * zoom
                                            grab = Offset(
                                                centerWorld.x + (p.x - w / 2f) / sc,
                                                centerWorld.y + (p.y - h / 2f) / sc,
                                            )
                                        }
                                        last = p
                                        lastMove = p
                                        lastMoveTime = now
                                        velocity = Offset.Zero
                                    } else {
                                        val reanchor = pinchReanchor
                                        if (reanchor != null &&
                                            kotlin.math.hypot(p.x - reanchor.x, p.y - reanchor.y) <= panSlop
                                        ) {
                                            // Ignore residual motion from the pinch before treating the
                                            // surviving finger as a fresh one-finger pan.
                                            last = p
                                            lastMove = p
                                            lastMoveTime = now
                                            velocity = Offset.Zero
                                        } else {
                                            pinchReanchor = null
                                            lastLocked = true
                                            val sc = bs * zoom
                                            val g = grab ?: Offset(
                                                centerWorld.x + (p.x - w / 2f) / sc,
                                                centerWorld.y + (p.y - h / 2f) / sc,
                                            ).also { grab = it }
                                            centerWorld = clampCenter(
                                                Offset(
                                                    g.x - (p.x - w / 2f) / sc,
                                                    g.y - (p.y - h / 2f) / sc,
                                                ),
                                                sc, geometry, w, h, rubber = true
                                            )
                                            val dt = ((now - lastMoveTime).coerceAtLeast(1L)).toFloat()
                                            val instant = Offset((p.x - lastMove.x) / dt, (p.y - lastMove.y) / dt)
                                            // Weight the newest motion heavily so a fast flick keeps its
                                            // peak speed instead of being smoothed into a gentle value.
                                            velocity = Offset(
                                                velocity.x * 0.35f + instant.x * 0.65f,
                                                velocity.y * 0.35f + instant.y * 0.65f,
                                            )
                                            last = p
                                            lastMove = p
                                            lastMoveTime = now
                                        }
                                    }
                                }
                                else -> break
                            }
                            prevPointCount = act.size
                            ev.changes.forEach { it.consume() }
                        }
                        if (dragging) {
                            flingJob?.cancel()
                            zoomFlingJob?.cancel()
                            settleJob?.cancel()
                            val releaseTime = SystemClock.uptimeMillis()
                            val ds0 = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                            val sc0 = baseScaleFor(geometry, ds0.width, ds0.height) * zoom
                            val settled = clampCenter(centerWorld, sc0, geometry, ds0.width, ds0.height, rubber = false, locked = lastLocked)
                            val outside = settled.x != centerWorld.x || settled.y != centerWorld.y
                            if (outside) {
                                // Pushed past the edge-to-centre bound: ease the map back, consistent
                                // at every zoom level.
                                val start = centerWorld
                                settleJob = pointerScope.launch {
                                    // Frame-synced ease-out (withFrameNanos suspends per frame) — a
                                    // SystemClock busy-loop here would hammer Compose state and lag.
                                    var elapsed = 0f
                                    var lastFrame = withFrameNanos { it }
                                    while (true) {
                                        val frame = withFrameNanos { it }
                                        elapsed += (frame - lastFrame) / 1_000_000f
                                        lastFrame = frame
                                        val t = (elapsed / SPRING_BACK_MS).coerceIn(0f, 1f)
                                        val e = 1f - (1f - t) * (1f - t) * (1f - t)
                                        centerWorld = Offset(start.x + (settled.x - start.x) * e, start.y + (settled.y - start.y) * e)
                                        if (t >= 1f) break
                                    }
                                }
                            } else {
                                // Prefer a pinch zoom glide when the last motion was a fresh pinch,
                                // otherwise a one-finger pan glide. This makes pinch-release always
                                // feel zoom inertia even when one finger lifts first.
                                val freshZoom = if (releaseTime - lastPinchTime <= 50L) shapedZoomVelocity(zoomVelocity) else 0f
                                val freshPan = if (releaseTime - lastMoveTime <= 50L) shapedFlingVelocity(velocity) else Offset.Zero
                                if (kotlin.math.abs(freshZoom) > ZOOM_FLING_MIN_VELOCITY) {
                                    // Continue the zoom around the screen centre (not the last
                                    // finger/centroid) so the inertia keeps the position fixed —
                                    // pivoting on the lifted fingers read as a "jump" on release.
                                    val pivot = Offset(ds0.width / 2f, ds0.height / 2f)
                                    zoomFlingJob = pointerScope.launch {
                                        var zv = freshZoom
                                        var lastFrame = withFrameNanos { it }
                                        while (kotlin.math.abs(zv) > ZOOM_FLING_MIN_VELOCITY) {
                                            val frame = withFrameNanos { it }
                                            val dtMs = ((frame - lastFrame) / 1_000_000f).coerceIn(0f, 34f)
                                            lastFrame = frame
                                            if (dtMs > 0f) {
                                                val factor = kotlin.math.exp(zv * dtMs)
                                                if (factor != 1f) {
                                                    val ds = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                                                    val bs = baseScaleFor(geometry, ds.width, ds.height)
                                                    val (nz, nc) = zoomAround(centerWorld, zoom, bs, pivot, factor, geometry, ds.width, ds.height)
                                                    zoom = nz
                                                    centerWorld = clampCenter(nc, bs * nz, geometry, ds.width, ds.height, locked = false)
                                                }
                                            }
                                            zv *= kotlin.math.exp(-dtMs / ZOOM_FLING_DECAY_MS)
                                        }
                                    }
                                } else if (freshPan.length() > FLING_MIN_VELOCITY) {
                                    flingJob = pointerScope.launch {
                                        var pv = freshPan
                                        var lastFrame = withFrameNanos { it }
                                         while (pv.length() > FLING_STOP_VELOCITY) {
                                            val frame = withFrameNanos { it }
                                            val dtMs = ((frame - lastFrame) / 1_000_000f).coerceIn(0f, 34f)
                                            lastFrame = frame
                                             if (dtMs > 0f) {
                                                 val ds = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                                                 val sc = baseScaleFor(geometry, ds.width, ds.height) * zoom
                                                 val unclamped = centerWorld - Offset(pv.x * dtMs / sc, pv.y * dtMs / sc)
                                                 val clamped = clampCenter(unclamped, sc, geometry, ds.width, ds.height)
                                                  centerWorld = clamped
                                                  // Hit the bound? Ease into a slide along it: damp the into-the-edge velocity component
                                                  // over a couple of frames instead of snapping direction ("bumping into a wall").
                                                  pv = Offset(
                                                      if (clamped.x != unclamped.x) pv.x * FLING_EDGE_SLIDE_DAMPING else pv.x,
                                                      if (clamped.y != unclamped.y) pv.y * FLING_EDGE_SLIDE_DAMPING else pv.y,
                                                  )
                                             }
                                             val decay = kotlin.math.exp(-dtMs / FLING_DECAY_MS)
                                             pv = Offset(pv.x * decay, pv.y * decay)
                                        }
                                    }
                                }
                            }
                        } else if (SystemClock.uptimeMillis() - downTime <= 500L) {
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
                    val invSc = 1.0f / sc
                    val cullPad = 2.0f * invSc
                    val vxMin = centerWorld.x - (ds.width * invSc) / 2f - cullPad
                    val vyMin = centerWorld.y - (ds.height * invSc) / 2f - cullPad
                    val vxMax = centerWorld.x + (ds.width * invSc) / 2f + cullPad
                    val vyMax = centerWorld.y + (ds.height * invSc) / 2f + cullPad
                    // Pass 1: fills. Pass 2: borders on top of every fill so shared borders are
                    // crisp (not overdrawn by a neighbour's fill). Width is constant on screen.
                    for (s in drawShapes) {
                        if (ENABLE_VIEWPORT_CULLING && !s.bbox.intersectsViewport(vxMin, vyMin, vxMax, vyMax)) continue
                        drawPath(s.path, s.fill)
                    }
                    for (s in drawShapes) {
                        if (ENABLE_VIEWPORT_CULLING && !s.bbox.intersectsViewport(vxMin, vyMin, vxMax, vyMax)) continue
                        drawPath(s.path, LAND_STROKE, style = Stroke(width = hair))
                    }
                    selected?.let { sel ->
                        drawShapes.firstOrNull { it.iso == sel }?.let { s ->
                            if (!ENABLE_VIEWPORT_CULLING || s.bbox.intersectsViewport(vxMin, vyMin, vxMax, vyMax)) {
                                drawPath(s.path, Color.White, style = Stroke(width = 2.2f / sc))
                            }
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

/** The canvas scale at `zoom == 1`: an exact world fit. */
private fun baseScaleFor(geometry: WorldMapData, w: Float, h: Float): Float =
    fitScale(geometry, w, h) * MIN_ZOOM_BUFFER

/**
 * Pan bounds. A one-finger pan is [locked]: fully zoomed out the map stays centred (swipes rubber
 * band back), then the free range widens toward [0, extent] as you zoom in. A pinch is unlocked so
 * the centre may always reach a map edge — this is what lets you zoom straight into edge islands
 * like Svalbard instead of being pulled back to the default centre. With [rubber] set (an active
 * one-finger pan) an overshoot past the bound is damped so the map resists before it springs back.
 */
private fun clampCenter(
    center: Offset,
    scale: Float,
    geometry: WorldMapData,
    w: Float,
    h: Float,
    rubber: Boolean = false,
    locked: Boolean = true,
): Offset {
    val x = clampAxis(center.x, geometry.width.toFloat(), scale, w, geometry.width * DEFAULT_CENTER_X_FRACTION, rubber, locked)
    val y = clampAxis(center.y, geometry.height.toFloat(), scale, h, geometry.height * 0.5f, rubber, locked)
    return Offset(x, y)
}

private fun clampAxis(center: Float, extent: Float, scale: Float, viewSize: Float, rest: Float, rubber: Boolean, locked: Boolean): Float {
    // Locked (pan): a point at [rest] when the whole axis fits on screen, widening to [0, extent]
    // as you zoom in. Unlocked (pinch): always [0, extent], so the centre can reach any map edge.
    val (minC, maxC) = if (locked) {
        // [k] is 0 at (and out to) the default framing and reaches 1 once the map is
        // ~1/EDGE_REACH_VISIBLE_RATIO times the screen, so the free range opens toward the edges
        // as you zoom in.
        val visibleRatio = (viewSize / scale / extent).coerceIn(0f, 1f)
        val k = ((1f - visibleRatio) / (1f - EDGE_REACH_VISIBLE_RATIO)).coerceIn(0f, 1f)
        rest * (1f - k) to rest + (extent - rest) * k
    } else {
        0f to extent
    }
    if (center in minC..maxC) return center
    if (!rubber) return center.coerceIn(minC, maxC)
    // Past the bound: damp the overshoot in screen pixels so the resistance feels the same at every
    // zoom level and asymptotes to a small maximum travel rather than letting the map sail off.
    val overshootPx = if (center < minC) (minC - center) * scale else (center - maxC) * scale
    val bandedPx = rubberBand(overshootPx, RUBBER_BAND_MAX_FRACTION * viewSize)
    return if (center < minC) minC - bandedPx / scale else maxC + bandedPx / scale
}

/**
 * Android-style rubber band: a raw pixel overshoot [delta] is mapped to a damped one that starts at
 * [RUBBER_BAND_FRICTION] of the finger speed and asymptotes to [dimension] (the maximum travel).
 */
private fun rubberBand(delta: Float, dimension: Float): Float {
    if (delta <= 0f) return 0f
    return dimension * (1f - 1f / (delta * RUBBER_BAND_FRICTION / dimension + 1f))
}

/**
 * Remap raw release velocity to a bounded fling velocity. Low speeds are boosted slightly so gentle
 * swipes still glide, while fast flicks saturate so the map does not coast across the whole screen.
 */
private fun shapedFlingVelocity(raw: Offset): Offset =
    Offset(
        (raw.x * FLING_GAIN).coerceIn(-FLING_MAX_X, FLING_MAX_X),
        (raw.y * FLING_GAIN).coerceIn(-FLING_MAX_Y, FLING_MAX_Y),
    )

private fun shapedZoomVelocity(raw: Float): Float {
    if (raw == 0f) return 0f
    val target = ZOOM_FLING_MAX_VELOCITY * (1f - kotlin.math.exp(-kotlin.math.abs(raw) / ZOOM_FLING_VELOCITY_SCALE))
    return if (raw > 0f) target else -target
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
    // Unlocked: a zoom may centre on any map edge, so edge islands (Svalbard, Samoa) stay reachable.
    return nz to clampCenter(Offset(ncx, ncy), ns, geometry, w, h, locked = false)
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
