package com.cbkres.visavole.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stay-rule row decoding both repositories share. A zone name that is NULL must fall through
 * to the zone id in both, which is the case the two copies used to answer differently.
 */
class RuleParsingTest {

    @Test
    fun zoneNameWinsWhenPresent() {
        assertEquals("Schengen Area", zoneLabel("Schengen Area", "schengen"))
    }

    @Test
    fun nullZoneNameFallsBackToTheZoneId() {
        assertEquals("schengen", zoneLabel(null, "schengen"))
    }

    @Test
    fun aRowWithNeitherLabelIsPlainlyEmpty() {
        assertEquals("", zoneLabel(null, null))
    }

    @Test
    fun entryTypesTrimAndDropBlanks() {
        assertEquals(setOf("double", "multiple"), parseEntryTypes("double, multiple"))
        assertEquals(setOf("single"), parseEntryTypes("single,,,"))
        assertEquals(emptySet<String>(), parseEntryTypes(null))
        assertEquals(emptySet<String>(), parseEntryTypes("  "))
    }

    @Test
    fun aJsonEncodedListParsesToTheSameTypesAsCsv() {
        // A quoted token that failed to match would silently void the entry-type gate.
        assertEquals(setOf("double", "multiple"), parseEntryTypes("[\"double\",\"multiple\"]"))
        assertEquals(setOf("single"), parseEntryTypes("[\"single\"]"))
        assertEquals("a literal null in the list is not an entry type", setOf("multiple"), parseEntryTypes("null, multiple"))
        assertEquals(emptySet<String>(), parseEntryTypes("[]"))
    }
}
