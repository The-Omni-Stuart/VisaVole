package com.cbkres.visavole.ui.screens.map

import androidx.compose.ui.geometry.Offset
import com.cbkres.visavole.data.WorldMapData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the extracted gesture math (MapGestures.kt): pan bounds, rubber banding, release-velocity
 * shaping and focal zoom — expected values derive from the constants as shipped, so this is a
 * behaviour freeze for the split out of WorldMapCanvas.kt, not a redesign.
 */
class MapGestureMathTest {

    private val geometry = WorldMapData(1000, 500, emptyMap())

    @Test fun fitScaleUsesTheTighterAxis() {
        assertEquals(0.8f, fitScale(geometry, 800f, 400f), 1e-5f)
        assertEquals(0.3f, fitScale(geometry, 300f, 400f), 1e-5f)
    }

    @Test fun baseScaleIsTheExactWorldFitAtMinimumZoom() {
        assertEquals(fitScale(geometry, 800f, 400f), baseScaleFor(geometry, 800f, 400f), 0f)
    }

    @Test fun clampAxisLeavesInBoundCentresAlone() {
        assertEquals(250f, clampAxis(250f, 1000f, 1f, 100f, 500f, rubber = false, locked = false), 0f)
        assertEquals(0f, clampAxis(0f, 1000f, 1f, 100f, 500f, rubber = false, locked = false), 0f)
        assertEquals(1000f, clampAxis(1000f, 1000f, 1f, 100f, 500f, rubber = false, locked = false), 0f)
    }

    @Test fun clampAxisCoercesOvershootWhenUnlocked() {
        // An unlocked (pinch) axis is the full map extent, hard-clamped without rubber.
        assertEquals(0f, clampAxis(-50f, 1000f, 1f, 100f, 500f, rubber = false, locked = false), 0f)
        assertEquals(1000f, clampAxis(1200f, 1000f, 1f, 100f, 500f, rubber = false, locked = false), 0f)
    }

    @Test fun clampAxisHoldsRestAtTheDefaultFramingWhenLocked() {
        // scale 0.1 shows the whole axis, so the locked free range has not opened: both bounds
        // collapse to the rest point.
        assertEquals(500f, clampAxis(400f, 1000f, 0.1f, 100f, 500f, rubber = false, locked = true), 0f)
        assertEquals(500f, clampAxis(900f, 1000f, 0.1f, 100f, 500f, rubber = false, locked = true), 0f)
        assertEquals(500f, clampAxis(500f, 1000f, 0.1f, 100f, 500f, rubber = false, locked = true), 0f)
    }

    @Test fun clampAxisOpensTowardTheEdgesAsScaleRises() {
        // scale 0.12: visibleRatio 0.833 → k 0.556 → free range [222.22, 777.78] around rest 500.
        assertEquals(222.2223f, clampAxis(100f, 1000f, 0.12f, 100f, 500f, rubber = false, locked = true), 1e-3f)
        assertEquals(600f, clampAxis(600f, 1000f, 0.12f, 100f, 500f, rubber = false, locked = true), 0f)
        assertEquals(777.7777f, clampAxis(900f, 1000f, 0.12f, 100f, 500f, rubber = false, locked = true), 1e-3f)
    }

    @Test fun clampAxisRubberBandsTheOvershootBothWays() {
        // 100 px past either bound (scale 1, view 100) → rubberBand(100, 0.4*100) ≈ 21.18:
        // damped below the raw overshoot, sign preserved, in-bound centres untouched.
        val low = clampAxis(-100f, 1000f, 1f, 100f, 500f, rubber = true, locked = false)
        assertEquals(-21.176472f, low, 1e-4f)
        assertTrue(low < 0f && low > -100f)
        val high = clampAxis(1100f, 1000f, 1f, 100f, 500f, rubber = true, locked = false)
        assertEquals(1021.17645f, high, 5e-4f)
        assertTrue(high > 1000f && high < 1100f)
        assertEquals(500f, clampAxis(500f, 1000f, 1f, 100f, 500f, rubber = true, locked = false), 0f)
    }

    @Test fun clampCenterKeepsTheCentreInsideTheMapWhenUnlocked() {
        val c = clampCenter(Offset(1200f, -50f), 2f, geometry, 100f, 100f, locked = false)
        assertEquals(Offset(1000f, 0f), c)
    }

    @Test fun clampCenterLocksToTheGeometricCentreFullyZoomedOut() {
        val c = clampCenter(Offset(20f, 40f), 0.05f, geometry, 50f, 25f, locked = true)
        assertEquals(Offset(500f, 250f), c)
    }

    @Test fun zoomAroundKeepsTheFocalWorldPointUnderThePivot() {
        val (nz, nc) = zoomAround(Offset(500f, 250f), 2f, 1f, Offset(600f, 400f), 1.5f, geometry, 800f, 600f)
        assertEquals(3f, nz, 0f)
        assertEquals(533.3333f, nc.x, 1e-3f)
        assertEquals(266.66666f, nc.y, 1e-4f)
        // screen = canvas centre + (world − centre) × scale: the world point under the pivot must
        // land on the pivot again after the zoom.
        val focal = Offset(600f, 300f)
        val before = Offset(800f / 2f + (focal.x - 500f) * 2f, 600f / 2f + (focal.y - 250f) * 2f)
        val after = Offset(800f / 2f + (focal.x - nc.x) * 3f, 600f / 2f + (focal.y - nc.y) * 3f)
        assertEquals(600f, before.x, 0f)
        assertEquals(400f, before.y, 0f)
        assertEquals(600f, after.x, 0.01f)
        assertEquals(400f, after.y, 0.01f)
    }

    @Test fun zoomAroundWithCentrePivotKeepsTheCentre() {
        val (nz, nc) = zoomAround(Offset(500f, 250f), 2f, 1f, Offset(400f, 300f), 4f, geometry, 800f, 600f)
        assertEquals(8f, nz, 0f)
        assertEquals(Offset(500f, 250f), nc)
    }

    @Test fun zoomAroundHoldsAtTheZoomLimitsUntouched() {
        // When the clamped zoom equals the incoming one, the function returns the input pair
        // verbatim — the centre is not even re-derived at MAX or MIN.
        val atMax = zoomAround(Offset(500f, 250f), MAX_ZOOM, 1f, Offset(600f, 400f), 2f, geometry, 800f, 600f)
        assertEquals(MAX_ZOOM, atMax.first, 0f)
        assertEquals(Offset(500f, 250f), atMax.second)
        val atMin = zoomAround(Offset(500f, 250f), MIN_ZOOM, 1f, Offset(600f, 400f), 0.5f, geometry, 800f, 600f)
        assertEquals(MIN_ZOOM, atMin.first, 0f)
        assertEquals(Offset(500f, 250f), atMin.second)
    }

    @Test fun zoomAroundClampsIntoMaxAndStillShiftsTheCentre() {
        val (nz, nc) = zoomAround(Offset(500f, 250f), 70f, 1f, Offset(600f, 400f), 2f, geometry, 800f, 600f)
        assertEquals(MAX_ZOOM, nz, 0f)
        assertEquals(500.35715f, nc.x, 1e-3f)
        assertEquals(250.17857f, nc.y, 1e-4f)
    }

    @Test fun rubberBandNeverMovesOnNonPositiveInput() {
        assertEquals(0f, rubberBand(0f, 40f), 0f)
        assertEquals(0f, rubberBand(-5f, 40f), 0f)
    }

    @Test fun rubberBandGrowsMonotonicallyAndStaysBelowTheCap() {
        assertEquals(0.4306215f, rubberBand(1f, 10f), 1e-5f)
        assertEquals(12.413795f, rubberBand(40f, 40f), 1e-4f)
        assertEquals(21.176472f, rubberBand(100f, 40f), 1e-4f)
        assertEquals(36.73469f, rubberBand(1000f, 40f), 1e-4f)
        assertTrue(rubberBand(40f, 40f) < rubberBand(100f, 40f))
        assertTrue(rubberBand(100f, 40f) < rubberBand(1000f, 40f))
        assertTrue(rubberBand(1000f, 40f) < 40f)
    }

    @Test fun shapedZoomVelocitySaturatesInBothDirections() {
        assertEquals(0f, shapedZoomVelocity(0f), 0f)
        assertEquals(0.0025284823f, shapedZoomVelocity(0.003f), 1e-7f)
        assertEquals(-0.0025284823f, shapedZoomVelocity(-0.003f), 1e-7f)
        assertTrue(shapedZoomVelocity(0.001f) < shapedZoomVelocity(0.003f))
        assertTrue(shapedZoomVelocity(0.003f) < shapedZoomVelocity(0.01f))
        assertEquals(0.004f, shapedZoomVelocity(10f), 0f)
        assertEquals(-0.004f, shapedZoomVelocity(-10f), 0f)
    }

    @Test fun shapedFlingVelocityBoostsThenClampsAsymmetrically() {
        assertEquals(Offset.Zero, shapedFlingVelocity(Offset.Zero))
        assertEquals(Offset(0.6f, 0.6f), shapedFlingVelocity(Offset(0.5f, 0.5f)))
        // The y cap (3.0) is lower than the x cap (4.5): a hard vertical flick gains less.
        assertEquals(Offset(4.5f, 3f), shapedFlingVelocity(Offset(10f, 10f)))
        assertEquals(Offset(-4.5f, -3f), shapedFlingVelocity(Offset(-10f, -10f)))
        assertEquals(3.6f, shapedFlingVelocity(Offset(3.0f, 2.6f)).x, 1e-5f)
        assertEquals(3f, shapedFlingVelocity(Offset(3.0f, 2.6f)).y, 0f)
    }
}
