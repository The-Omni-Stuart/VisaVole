package com.cbkres.visavole.ui.screens.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.cbkres.visavole.data.CountryShape
import com.cbkres.visavole.data.WorldMapData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure-geometry surface (MapGeometry.kt) at the behaviour the canvas was built on, so
 * the extraction from WorldMapCanvas.kt cannot shift a hit test, an area or a tap-focus.
 */
class MapGeometryTest {

    // A ring is an implicitly closed point list (the closing edge runs last→first).
    private val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)

    private fun world(vararg countries: Pair<String, List<FloatArray>>): WorldMapData =
        WorldMapData(1000, 500, countries.associate { (iso, rings) -> iso to CountryShape(iso, rings) })

    @Test fun aHostDoesNotShrinkForAnEnclave() {
        val host = PlacedMapLabel("ZA", "South Africa", 0f, 0f, Rect(0f, 0f, 20f, 10f), BBox(0f, 0f, 100f, 100f))
        assertEquals(false, canDowngradeFor(host, 50f, 50f, 40f))
        assertEquals(true, canDowngradeFor(host.copy(bbox = BBox(200f, 200f, 240f, 240f)), 50f, 50f, 40f))
        assertEquals(false, canDowngradeFor(host.copy(bbox = BBox(200f, 200f, 300f, 300f)), 50f, 50f, 20f))
        assertEquals(false, canDowngradeFor(host.copy(text = "ZA"), 150f, 150f, 40f))
    }

    @Test fun labelRectsDetectRealOverlapsOnly() {
        val a = Rect(0f, 0f, 20f, 10f)
        assertEquals(true, rectsOverlap(a, Rect(15f, 5f, 30f, 12f)))
        assertEquals(false, rectsOverlap(a, Rect(25f, 0f, 40f, 10f)))
    }

    @Test fun labelsWaitForZoomAndClearEachOther() {
        assertEquals(null, mapLabel("AT", "Austria", 200f, 1f))
        assertEquals("AT", mapLabel("AT", "Austria", 200f, 1.5f))
        assertEquals("AT", mapLabel("AT", "Austria", 200f, 2.4f))
        assertEquals("Austria", mapLabel("AT", "Austria", 200f, 2.5f))
        val lShape = floatArrayOf(0f, 0f, 20f, 0f, 20f, 10f, 10f, 10f, 10f, 20f, 0f, 20f)
        val anchor = interiorLabelPoint(listOf(lShape))!!
        assertEquals(true, pointInRing(lShape, anchor.x, anchor.y))
        assertEquals(true, anchor.x < 10f && anchor.y < 10f)
        assertEquals(false, labelFits(listOf(Rect(0f, 0f, 20f, 10f)), Rect(15f, 5f, 30f, 12f)))
        assertEquals(true, labelFits(listOf(Rect(0f, 0f, 20f, 10f)), Rect(25f, 0f, 40f, 10f)))
    }

    @Test fun wideStripLabelsAtItsMiddleNotItsEdge() {
        // Antarctica's projected shape is a strip: clearance ties all along it, so the anchor
        // must fall mid-width, not at the first grid point the sweep reaches.
        val strip = floatArrayOf(0f, 0f, 100f, 0f, 100f, 10f, 0f, 10f)
        val anchor = interiorLabelPoint(listOf(strip))!!
        assertEquals(true, anchor.y in 3f..7f)
        assertEquals(true, kotlin.math.abs(anchor.x - 50f) <= 6f)
    }

    @Test fun jaggedStripStillLabelsNearItsMiddle() {
        // Real coastlines never tie, so the centre preference needs a clearance band, not a
        // tie: this strip's deepest point sits right of middle, yet the label belongs central.
        val jag = floatArrayOf(
            0f, 0f, 100f, 0f, 100f, 14f, 90f, 14f, 84f, 4f, 70f, 14f, 60f, 4f,
            50f, 14f, 40f, 4f, 26f, 14f, 16f, 4f, 0f, 10f,
        )
        val anchor = interiorLabelPoint(listOf(jag))!!
        assertEquals(true, kotlin.math.abs(anchor.x - 50f) <= 12f)
    }

    @Test fun sameWoundEnclaveIsReversedButOppositeWoundIsLeftAlone() {
        val outer = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val same = floatArrayOf(20f, 20f, 40f, 20f, 40f, 40f, 20f, 40f)
        val opposite = floatArrayOf(20f, 20f, 20f, 40f, 40f, 40f, 40f, 20f)
        assertEquals(setOf(1), ringsToReverse(listOf(outer, same)))
        assertEquals(emptySet<Int>(), ringsToReverse(listOf(outer, opposite)))
    }

    @Test fun pointInRingClassifiesSquareInteriorAndExterior() {
        assertTrue(pointInRing(square, 5f, 5f))
        assertFalse(pointInRing(square, 15f, 5f))
        assertFalse(pointInRing(square, -5f, 5f))
        assertFalse(pointInRing(square, 5f, 15f))
        assertFalse(pointInRing(square, 5f, -5f))
    }

    @Test fun pointInRingUsesTheHalfOpenEdgeConvention() {
        // The ray counts edges crossing strictly above-right of the point: the left/bottom edges
        // contain their border points, the right/top edges do not.
        assertTrue(pointInRing(square, 0f, 5f))
        assertTrue(pointInRing(square, 5f, 0f))
        assertFalse(pointInRing(square, 10f, 5f))
        assertFalse(pointInRing(square, 5f, 10f))
    }

    @Test fun pointInRingCountsThroughTheClosingSeamEdge() {
        // Left-facing triangle: the closing seam (10,10)→(10,0) is the only edge crossing y=5.
        val seam = floatArrayOf(10f, 0f, 0f, 5f, 10f, 10f)
        assertTrue(pointInRing(seam, 5f, 5f))
        assertFalse(pointInRing(seam, 15f, 5f))
        assertFalse(pointInRing(seam, -5f, 5f))
    }

    @Test fun pointInRingIgnoresDegenerateRings() {
        assertFalse(pointInRing(FloatArray(0), 5f, 5f))
        assertFalse(pointInRing(floatArrayOf(5f, 5f), 5f, 5f))
        // A two-point "ring" ray-casts its segment twice (each direction) and cancels out.
        assertFalse(pointInRing(floatArrayOf(0f, 0f, 10f, 10f), 4.99f, 4.99f))
        assertFalse(pointInRing(floatArrayOf(0f, 0f, 10f, 10f), 5f, 5f))
    }

    @Test fun polygonAreaIsWindingIndependentInMagnitude() {
        val clockwise = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val counterClockwise = floatArrayOf(0f, 0f, 0f, 10f, 10f, 10f, 10f, 0f)
        // abs() in the function: both windings read +100, which is what buildFocus ranks rings by.
        assertEquals(100f, polygonArea(clockwise), 0f)
        assertEquals(100f, polygonArea(counterClockwise), 0f)
        assertEquals(50f, polygonArea(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)), 0f)
    }

    @Test fun polygonAreaOfDegenerateRingsIsZero() {
        assertEquals(0f, polygonArea(FloatArray(0)), 0f)
        assertEquals(0f, polygonArea(floatArrayOf(5f, 5f)), 0f)
        assertEquals(0f, polygonArea(floatArrayOf(0f, 0f, 10f, 10f)), 0f)
    }

    @Test fun bboxOfUnionsEveryRing() {
        val b = bboxOf(listOf(floatArrayOf(0f, 0f, 10f, 10f), floatArrayOf(20f, -5f, 30f, 3f)))
        assertEquals(BBox(0f, -5f, 30f, 10f), b)
        assertEquals(15f, b.cx, 0f)
        assertEquals(2.5f, b.cy, 0f)
        assertEquals(30f, b.w, 0f)
        assertEquals(15f, b.h, 0f)
    }

    @Test fun bboxOfEmptyInputFallsBackToAUnitBox() {
        assertEquals(BBox(0f, 0f, 1f, 1f), bboxOf(emptyList()))
        assertEquals(BBox(0f, 0f, 1f, 1f), bboxOf(listOf(FloatArray(0))))
    }

    @Test fun bboxHelpersClampExtentsAndIncludeBoundaries() {
        val point = bboxOf(listOf(floatArrayOf(7f, 7f, 7f, 7f)))
        assertEquals(1f, point.w, 0f)
        assertEquals(1f, point.h, 0f)
        assertTrue(point.contains(7f, 7f))
        assertFalse(point.contains(8f, 8f))
        assertTrue(point.intersectsViewport(7f, 7f, 20f, 20f))
        assertFalse(point.intersectsViewport(8f, 8f, 20f, 20f))
    }

    @Test fun buildShapesSumsRingAreasAndUnionsBBoxes() {
        val far = floatArrayOf(100f, 100f, 105f, 100f, 105f, 105f, 100f, 105f)
        val s = buildShapes(world("TW" to listOf(square, far))).single()
        assertEquals("TW", s.iso)
        assertEquals(125f, s.area, 0f)
        assertEquals(BBox(0f, 0f, 105f, 105f), s.bbox)
        assertEquals(2, s.rings.size)
    }

    @Test fun buildFocusKeepsNearIslandsAndDropsOverseasTerritory() {
        val core = floatArrayOf(100f, 100f, 200f, 100f, 200f, 200f, 100f, 200f)
        val island = floatArrayOf(90f, 60f, 110f, 60f, 110f, 80f, 90f, 80f)
        val territory = floatArrayOf(140f, 500f, 160f, 500f, 160f, 520f, 140f, 520f)
        val focus = buildFocus(world("FR" to listOf(core, island, territory))).getValue("FR")
        assertEquals(BBox(90f, 60f, 200f, 200f), focus.bbox)
        assertEquals(Offset(145f, 130f), focus.point)
    }

    @Test fun buildFocusSkipsAZeroRingFeatureInsteadOfThrowing() {
        // WorldGeometry permits ringCount == 0; buildFocus runs at load and must not throw on it.
        val focus = buildFocus(world("AQ" to emptyList(), "FR" to listOf(square)))
        assertNull("a ringless feature cannot be focused", focus["AQ"])
        assertEquals(BBox(0f, 0f, 10f, 10f), focus.getValue("FR").bbox)
    }

    @Test fun buildFocusTightensTheGbMarginForItsTapZoom() {
        val core = floatArrayOf(100f, 100f, 200f, 100f, 200f, 200f, 100f, 200f)
        // A territory 4% of the world width south of the mainland: inside FR's 7% margin, outside
        // GB's 3% override — the Gibraltar behaviour from the override's comment.
        val south = floatArrayOf(150f, 240f, 160f, 240f, 160f, 250f, 150f, 250f)
        assertEquals(BBox(100f, 100f, 200f, 250f), buildFocus(world("FR" to listOf(core, south))).getValue("FR").bbox)
        assertEquals(BBox(100f, 100f, 200f, 200f), buildFocus(world("GB" to listOf(core, south))).getValue("GB").bbox)
        assertEquals(Offset(150f, 150f), buildFocus(world("GB" to listOf(core, south))).getValue("GB").point)
    }

    @Test fun buildFocusAnchorsOnTheLargestRingWhereverItIsListed() {
        val core = floatArrayOf(100f, 100f, 200f, 100f, 200f, 200f, 100f, 200f)
        val tiny = floatArrayOf(400f, 400f, 410f, 400f, 410f, 410f, 400f, 410f)
        assertEquals(BBox(100f, 100f, 200f, 200f), buildFocus(world("XX" to listOf(tiny, core))).getValue("XX").bbox)
    }

    @Test fun defaultCenterIsTheGeometricCentre() {
        assertEquals(Offset(500f, 250f), defaultCenter(world()))
        assertEquals(Offset(250f, 125f), defaultCenter(WorldMapData(500, 250, emptyMap())))
    }

    @Test fun hitTestResolvesDisjointSquaresByPoint() {
        val far = floatArrayOf(100f, 100f, 110f, 100f, 110f, 110f, 100f, 110f)
        val shapes = buildShapes(world("AA" to listOf(square), "BB" to listOf(far)))
        assertEquals("AA", hitTest(shapes, 5f, 5f))
        assertEquals("BB", hitTest(shapes, 105f, 105f))
        assertNull(hitTest(shapes, 50f, 5f))
    }

    @Test fun hitTestPrefersTheSmallerAreaRegardlessOfListOrder() {
        val big = floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f)
        val small = floatArrayOf(8f, 8f, 12f, 8f, 12f, 12f, 8f, 12f)
        val bigFirst = buildShapes(world("BIG" to listOf(big), "SML" to listOf(small)))
        val smallFirst = buildShapes(world("SML" to listOf(small), "BIG" to listOf(big)))
        // Enclave resolution: the shared point belongs to the smaller country either way.
        assertEquals("SML", hitTest(bigFirst, 10f, 10f))
        assertEquals("SML", hitTest(smallFirst, 10f, 10f))
        assertEquals("BIG", hitTest(bigFirst, 2f, 2f))
    }

    @Test fun hitTestTiesKeepTheFirstMatchInListOrder() {
        // Equal areas cannot both beat the incumbent (`area >= bestArea` skips), so list order
        // decides — deterministic, not data-dependent.
        val ordered = buildShapes(world("FR" to listOf(square), "ND" to listOf(square)))
        assertEquals("FR", hitTest(ordered, 5f, 5f))
        val flipped = buildShapes(world("ND" to listOf(square), "FR" to listOf(square)))
        assertEquals("ND", hitTest(flipped, 5f, 5f))
    }
}
