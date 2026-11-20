package com.cbkres.visavole.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reduced-motion gating: the OS animation scale of 0 is the user asking for no autonomous motion. */
class ReducedMotionTest {

    @Test
    fun zeroScaleMeansReducedMotion() {
        assertTrue(reducedMotionFromScale(0f))
        assertTrue(reducedMotionFromScale(-0.5f))
    }

    @Test
    fun normalScalesKeepMotionOn() {
        assertFalse(reducedMotionFromScale(1f))
        assertFalse(reducedMotionFromScale(0.5f))
        assertFalse(reducedMotionFromScale(2f))
    }
}
