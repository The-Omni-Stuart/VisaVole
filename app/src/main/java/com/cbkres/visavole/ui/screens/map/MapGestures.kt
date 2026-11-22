package com.cbkres.visavole.ui.screens.map

import androidx.compose.ui.geometry.Offset
import com.cbkres.visavole.data.WorldMapData
import kotlin.math.abs
import kotlin.math.exp

internal const val MIN_ZOOM = 1f
// High enough for sub-city states (VA, GI, MC…) to fill a useful part of the screen.
internal const val MAX_ZOOM = 400f
// The minimum zoom is an exact world fit so edge islands remain visible in the default view.
private const val MIN_ZOOM_BUFFER = 1f
// Pan bounds: fully zoomed out the map is locked to the default centre; as you zoom in the
// free-pan range widens so a map edge can reach the centre of the screen (edge islands like Samoa
// stay centreable). Pushing past the bound is rubber-banded and springs back on release.
private const val RUBBER_BAND_FRICTION = 0.45f
private const val RUBBER_BAND_MAX_FRACTION = 0.4f
// The pan bound reaches the map edges once the visible world is this fraction of the full extent
// (map ~1/0.7 ≈ 1.4x the screen). Generous so that at a moderate zoom (hunting around Europe) the
// map is already fully free — a fling has the whole map to travel and rarely meets a bound, which
// is what used to read as "bumping into a grid." Edge islands stay reachable by panning too.
private const val EDGE_REACH_VISIBLE_RATIO = 0.7f
// One-finger fling: raw (low-passed) screen px/ms passes through [FLING_GAIN] and is capped, so a
// hard flick clearly flings faster than a gentle one. The canvas's FLING_MIN_VELOCITY gate is set
// high enough that only a genuine fast flick starts a fling — quick short repositioning pans
// (especially at a mid-zoom where you're hunting for a country) must not fling.
private const val FLING_GAIN = 1.2f
private const val FLING_MAX_X = 4.5f
private const val FLING_MAX_Y = 3.0f
// Pinch zoom inertia: log-scale zoom velocity (per ms) with decay; capped so it glides, not rockets.
private const val ZOOM_FLING_MAX_VELOCITY = 0.004f
private const val ZOOM_FLING_VELOCITY_SCALE = 0.003f

/** Scale at which the whole world (width x height) fits inside a w x h canvas. */
internal fun fitScale(geometry: WorldMapData, w: Float, h: Float): Float =
    minOf(w / geometry.width, h / geometry.height)

/** The canvas scale at `zoom == 1`: an exact world fit. */
internal fun baseScaleFor(geometry: WorldMapData, w: Float, h: Float): Float =
    fitScale(geometry, w, h) * MIN_ZOOM_BUFFER

/**
 * Pan bounds. A one-finger pan is [locked]: fully zoomed out the map stays centred (swipes rubber
 * band back), then the free range widens toward [0, extent] as you zoom in. A pinch is unlocked so
 * the centre may always reach a map edge — this is what lets you zoom straight into edge islands
 * like Svalbard instead of being pulled back to the default centre. With [rubber] set (an active
 * one-finger pan) an overshoot past the bound is damped so the map resists before it springs back.
 * Fully zoomed out, a locked pan cannot leave the rest point at all.
 */
internal fun clampCenter(
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

internal fun clampAxis(center: Float, extent: Float, scale: Float, viewSize: Float, rest: Float, rubber: Boolean, locked: Boolean): Float {
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
internal fun rubberBand(delta: Float, dimension: Float): Float {
    if (delta <= 0f) return 0f
    return dimension * (1f - 1f / (delta * RUBBER_BAND_FRICTION / dimension + 1f))
}

/**
 * Remap raw release velocity to a bounded fling velocity. Low speeds are boosted slightly so gentle
 * swipes still glide, while fast flicks saturate so the map does not coast across the whole screen.
 */
internal fun shapedFlingVelocity(raw: Offset): Offset =
    Offset(
        (raw.x * FLING_GAIN).coerceIn(-FLING_MAX_X, FLING_MAX_X),
        (raw.y * FLING_GAIN).coerceIn(-FLING_MAX_Y, FLING_MAX_Y),
    )

internal fun shapedZoomVelocity(raw: Float): Float {
    if (raw == 0f) return 0f
    val target = ZOOM_FLING_MAX_VELOCITY * (1f - exp(-abs(raw) / ZOOM_FLING_VELOCITY_SCALE))
    return if (raw > 0f) target else -target
}

/** Zoom by [factor] keeping the world point under [pivot] fixed; returns (newZoom, newCenter). */
internal fun zoomAround(
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
