package com.cbkres.visavole.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionedIndicesTest {

    @Test
    fun headersShiftItemsAndEmptySectionsAddNothing() {
        val m = sectionedIndices(listOf(listOf("a1", "a2"), emptyList(), listOf("b1")))
        assertEquals(mapOf("a1" to 2, "a2" to 3, "b1" to 5), m)
    }

    @Test
    fun allEmptyGivesEmptyMap() {
        assertTrue(sectionedIndices(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun singleSectionStartsAtRowTwo() {
        assertEquals(mapOf("only" to 2), sectionedIndices(listOf(listOf("only"))))
    }
}
