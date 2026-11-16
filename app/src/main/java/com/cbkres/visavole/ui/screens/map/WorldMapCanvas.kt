package com.cbkres.visavole.ui.screens.map

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.ui.tapSink
import com.cbkres.visavole.data.WorldMapData
import com.cbkres.visavole.domain.Access
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

private const val SPRING_BACK_MS = 240f
private const val FLING_DECAY_MS = 180f
// A fling only starts from a genuine fast flick (release velocity above this, screen px/ms).
private const val FLING_MIN_VELOCITY = 0.8f
// The fling keeps decaying down to this much lower speed before it stops, so it eases into a
// smooth halt (a little extra glide) instead of cutting off abruptly at the trigger speed.
private const val FLING_STOP_VELOCITY = 0.08f
// When a fling reaches a bound it slides along it; this damps the into-the-edge velocity component
// per frame so the turn eases in over a couple of frames instead of snapping (less "grid" feel).
private const val FLING_EDGE_SLIDE_DAMPING = 0.3f
private const val ZOOM_FLING_DECAY_MS = 150f
private const val ZOOM_FLING_MIN_VELOCITY = 0.00015f
// Experimental performance switch: draw only country paths that intersect the visible canvas.
// Set to false to restore the original full-world draw path.
private const val ENABLE_VIEWPORT_CULLING = true

/**
 * Content identity of the map's colours: the [access]/[homeCountries]/[ownVisaCountries] sets
 * arrive as fresh instances on every publish, so folding their content-based hashCodes (Map/Set
 * contract, entries compared via [Access] equality) into one Int keys the `drawShapes` build by
 * what it paints, not by instance identity. A collision would keep stale fills until the next
 * content change — a risk bounded to one repaint.
 */
internal fun mapPaintKey(
    access: Map<String, Access>,
    homeCountries: Set<String>,
    ownVisaCountries: Set<String>,
): Int =
    (access.hashCode() * 31 + homeCountries.hashCode()) * 31 + ownVisaCountries.hashCode()

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
internal fun WorldMapCanvas(
    geometry: WorldMapData,
    access: Map<String, Access>,
    shapes: List<IsoShape>,
    focus: Map<String, Focus>,
    zoomState: MutableFloatState,
    centerState: MutableState<Offset>,
    selected: String?,
    // Bumped by search picks so re-picking the already-selected country re-runs the centring
    // tween even though `selected` does not change (map taps never bump it).
    recenterNonce: Int = 0,
    onCountryTap: (String?) -> Unit,
    homeCountries: Set<String> = emptySet(),
    ownVisaCountries: Set<String> = emptySet(),
    controlsBottomPadding: Dp = 24.dp,
    // The live bottom inset (controls + card) the centre-on-country tween aims above. The tween
    // reads it each frame so a card that grows/shrinks as its rows arrive eases into the fit
    // within one continuous ramp instead of restarting.
    centeringInsetNow: () -> Dp,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val topInsetPx = with(density) { topInset.toPx() }
    var controlsH by remember { mutableStateOf(0.dp) }
    // Keyed by content (see mapPaintKey): a publish that leaves the colours equal — e.g. starring
    // a passport — must not rebuild the ~204 fills and repaint the world.
    val paintKey = remember(access, homeCountries, ownVisaCountries) {
        mapPaintKey(access, homeCountries, ownVisaCountries)
    }
    val drawShapes: List<DrawShape> = remember(shapes, paintKey) {
        shapes.map { s ->
            val lvl = access[s.iso]?.level
            DrawShape(
                s.iso,
                s.path,
                s.bbox,
                mapFill(lvl, s.iso in homeCountries, s.iso in ownVisaCountries),
            )
        }
    }
    val latestOnTap by rememberUpdatedState(onCountryTap)
    val latestInset by rememberUpdatedState(centeringInsetNow)
    val latestSelected by rememberUpdatedState(selected)
    val pointerScope = rememberCoroutineScope()

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var zoom by zoomState
    var centerWorld by centerState

    // The scale at which the world fits the canvas at the minimum zoom (zoom == 1).
    val baseScale = if (canvasSize.width > 0f) baseScaleFor(geometry, canvasSize.width, canvasSize.height) else 1f

    // Job of the running centre-on-country tween, so a pan starting mid-tween can cancel it
    // (otherwise the tween keeps writing zoom/centre for up to 350 ms and the finger loses).
    var centeringJob by remember { mutableStateOf<Job?>(null) }

    // Smoothly centre and zoom in when a country becomes selected. Duration matches the alpha04
    // feel: longer tweens read as lag and fight a follow-up pan for longer.
    // NOT keyed on the bottom inset: the card's height settles a few frames into the selection
    // (rows arrive async, and on a switch the height morphs). Keying on it restarted the tween
    // mid-flight — the "wall". Instead each frame recomputes the fit from the current inset, so
    // one continuous ramp eases into the settled framing.
    LaunchedEffect(selected, recenterNonce, canvasSize, topInsetPx) {
        if (selected == null || canvasSize.width <= 0f) return@LaunchedEffect
        val f = focus[selected] ?: return@LaunchedEffect
        centeringJob = coroutineContext[Job]
        val w = canvasSize.width
        val h = canvasSize.height
        val top = topInsetPx.coerceIn(0f, h)
        val bs = baseScaleFor(geometry, w, h)
        val bottomInsetNow = { with(density) { (controlsH + latestInset() + 12.dp).toPx() } }
        // One fit computation from the current bottom inset.
        fun fitFor(bottom: Float): Pair<Float, Offset> {
            val visibleH = (h - top - bottom).coerceAtLeast(1f)
            val targetZoom = (minOf(w * 0.75f / f.bbox.w, visibleH * 0.75f / f.bbox.h) / bs).coerceIn(MIN_ZOOM, MAX_ZOOM)
            // The canvas itself is centred on `centerWorld`; shift the centre so the selected
            // country lands in the middle of the *visible* strip between the search bar and the bottom UI.
            val visibleCenterShift = Offset(0f, (top - bottom) / (2f * bs * targetZoom))
            val targetCenter = clampCenter(f.point - visibleCenterShift, bs * targetZoom, geometry, w, h, locked = false)
            return targetZoom to targetCenter
        }
        val fromZoom = zoom
        val fromCenter = centerWorld
        Animatable(0f).animateTo(1f, tween(350)) {
            val (targetZoom, targetCenter) = fitFor(bottomInsetNow().coerceIn(0f, h - top))
            val k = value
            zoom = fromZoom + (targetZoom - fromZoom) * k
            centerWorld = Offset(
                fromCenter.x + (targetCenter.x - fromCenter.x) * k,
                fromCenter.y + (targetCenter.y - fromCenter.y) * k,
            )
        }
        centeringJob = null
    }

    // Keep the draw lambda's IDENTITY stable across recompositions where nothing the map draws
    // has changed (e.g. the country-card slide animating controlsBottomPadding): a fresh lambda
    // per recomposition forces a full redraw of every fill and stroke, while the keyed captures
    // here (zoom/centre/selection/geometry) change exactly when a redraw is needed.
    // the ocean follows the effective theme (it was a fixed dark navy, so it stayed a dark
    // island under a light app). The bright country fills and navy borders read on both.
    val surf = MaterialTheme.colorScheme.surface
    val isDarkSurf = (0.299f * surf.red + 0.587f * surf.green + 0.114f * surf.blue) < 0.5f
    val ocean = if (isDarkSurf) OCEAN else OCEAN_LIGHT
    val mapDraw: DrawScope.() -> Unit = remember(geometry, drawShapes, selected, canvasSize, zoom, centerWorld, ocean) {
        {
            drawRect(ocean)
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
                    // hoist the border Stroke — it was allocated per shape per frame (~204/frame).
                    val borderStroke = Stroke(width = hair)
                    // Pass 1: fills. Pass 2: borders on top of every fill so shared borders are
                    // crisp (not overdrawn by a neighbour's fill). Width is constant on screen.
                    for (s in drawShapes) {
                        if (ENABLE_VIEWPORT_CULLING && !s.bbox.intersectsViewport(vxMin, vyMin, vxMax, vyMax)) continue
                        drawPath(s.path, s.fill)
                    }
                    for (s in drawShapes) {
                        if (ENABLE_VIEWPORT_CULLING && !s.bbox.intersectsViewport(vxMin, vyMin, vxMax, vyMax)) continue
                        drawPath(s.path, LAND_STROKE, style = borderStroke)
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
    }

    Box(modifier.clipToBounds()) {
        // the Canvas is the app's primary surface (all per-country colour) but had no
        // semantics, so TalkBack skipped it entirely. Give it a description — and, when a country
        // is selected, announce it.
        val mapDescription = selected?.let { iso -> geometry.countries[iso]?.name }
            ?.let { "World map. $it selected." }
            ?: "World map, visa access by country."
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = mapDescription }
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
                        centeringJob?.cancel()
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
                        var lastPinchIds: Set<PointerId>? = null
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
                                    // the pair is the two lowest-id pressed pointers, so if that set
                                    // changes (a finger lifts or a new one lands while 2+ are down) the
                                    // centroid/distance now come from a different pair — treating them as a
                                    // delta from the previous pair's values would jump the map. Re-anchor
                                    // instead, exactly like the fresh-pinch and 2→1 paths.
                                    val ids = act.map { it.id }.toSet()
                                    if (prevPointCount < 2 || ids != lastPinchIds) {
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
                                        val instant = ln(dist / lastDist) / dt
                                        zoomVelocity = zoomVelocity * 0.7f + instant * 0.3f
                                    }
                                    lastPinchIds = ids
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
                                    lastPinchIds = null
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
                                        if (hypot(p.x - down.position.x, p.y - down.position.y) > panSlop) {
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
                                            hypot(p.x - reanchor.x, p.y - reanchor.y) <= panSlop
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
                                if (abs(freshZoom) > ZOOM_FLING_MIN_VELOCITY) {
                                    // Continue the zoom around the screen centre (not the last
                                    // finger/centroid) so the inertia keeps the position fixed —
                                    // pivoting on the lifted fingers read as a "jump" on release.
                                    val pivot = Offset(ds0.width / 2f, ds0.height / 2f)
                                    zoomFlingJob = pointerScope.launch {
                                        var zv = freshZoom
                                        var lastFrame = withFrameNanos { it }
                                        while (abs(zv) > ZOOM_FLING_MIN_VELOCITY) {
                                            val frame = withFrameNanos { it }
                                            val dtMs = ((frame - lastFrame) / 1_000_000f).coerceIn(0f, 34f)
                                            lastFrame = frame
                                            if (dtMs > 0f) {
                                                val factor = exp(zv * dtMs)
                                                if (factor != 1f) {
                                                    val ds = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                                                    val bs = baseScaleFor(geometry, ds.width, ds.height)
                                                    val (nz, nc) = zoomAround(centerWorld, zoom, bs, pivot, factor, geometry, ds.width, ds.height)
                                                    zoom = nz
                                                    centerWorld = clampCenter(nc, bs * nz, geometry, ds.width, ds.height, locked = false)
                                                }
                                            }
                                            zv *= exp(-dtMs / ZOOM_FLING_DECAY_MS)
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
                                             val decay = exp(-dtMs / FLING_DECAY_MS)
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
            mapDraw()
        }

        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f
        val zoomCentered = { factor: Float ->
            val (nz, nc) = zoomAround(centerWorld, zoom, baseScale, Offset(cx, cy), factor, geometry, canvasSize.width, canvasSize.height)
            zoom = nz
            centerWorld = nc
        }
        MapZoomControls(
            controlsBottomPadding = controlsBottomPadding,
            onHeightChanged = { controlsH = it },
            onZoomIn = { zoomCentered(1.5f) },
            onZoomOut = { zoomCentered(1f / 1.5f) },
            onReset = {
                // Reset means "back to the whole world" — close the country card too, or it would
                // float over the zoomed-out view (the centring effect only runs on a selection).
                latestOnTap(null)
                zoom = 1f
                centerWorld = defaultCenter(geometry)
            },
        )
    }
}

/** The floating zoom-in / zoom-out / reset buttons in the map's bottom corner. */
@Composable
private fun BoxScope.MapZoomControls(
    controlsBottomPadding: Dp,
    onHeightChanged: (Dp) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
) {
    val density = LocalDensity.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .tapSink()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = controlsBottomPadding)
            .onSizeChanged { onHeightChanged(Dp(it.height / density.density)) },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(4.dp),
        ) {
            IconButton(onClick = onZoomIn) {
                Icon(Icons.Filled.Add, contentDescription = "Zoom in")
            }
            IconButton(onClick = onZoomOut) {
                // M3 IconButton has no contentDescription param; the label comes from child
                // semantics, which the clickable button merges.
                Text(
                    "-",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = "Zoom out" },
                )
            }
            IconButton(onClick = onReset) {
                Icon(Icons.Filled.Home, contentDescription = "Reset view")
            }
        }
    }
}
