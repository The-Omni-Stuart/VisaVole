package com.cbkres.visavole.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisaDbTest {

    @Test
    fun fingerprintUsesShaSidecarWhenPresent() {
        assertEquals("abc123|4096", VisaDb.fingerprint("abc123", 4096))
    }

    @Test
    fun fingerprintFallsBackToVersionConstantWithoutSidecar() {
        assertEquals("2|4096", VisaDb.fingerprint(null, 4096))
    }

    @Test
    fun shouldRecopyWhenDbFileMissing() {
        assertTrue(VisaDb.shouldRecopy(false, "2|4096", "2|4096"))
    }

    @Test
    fun shouldRecopyWhenMetaMissing() {
        assertTrue(VisaDb.shouldRecopy(true, null, "2|4096"))
    }

    @Test
    fun shouldRecopyWhenMetaStale() {
        assertTrue(VisaDb.shouldRecopy(true, "2|2048", "2|4096"))
    }

    @Test
    fun shouldNotRecopyWhenMetaMatchesAfterTrim() {
        assertFalse(VisaDb.shouldRecopy(true, "  2|4096\n", "2|4096"))
    }
}
