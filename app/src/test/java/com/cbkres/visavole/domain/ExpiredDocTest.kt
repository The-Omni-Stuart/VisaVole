package com.cbkres.visavole.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiredDocTest {

    private fun snap(kind: AllowanceKind, status: AllowanceStatus) =
        AllowanceSnapshot(key = "k", title = "t", kind = kind, status = status)

    @Test
    fun lapsedDocumentIsExpired() {
        assertTrue(snap(AllowanceKind.DOCUMENT_VALIDITY, AllowanceStatus.DANGER).isExpiredDoc)
        assertTrue(snap(AllowanceKind.ENTRY_COUNT, AllowanceStatus.DANGER).isExpiredDoc)
    }

    @Test
    fun exhaustedWindowIsNotAnExpiredDoc() {
        assertFalse(snap(AllowanceKind.ROLLING, AllowanceStatus.DANGER).isExpiredDoc)
        assertFalse(snap(AllowanceKind.PER_ENTRY, AllowanceStatus.DANGER).isExpiredDoc)
        assertFalse(snap(AllowanceKind.LIFETIME, AllowanceStatus.DANGER).isExpiredDoc)
    }

    @Test
    fun healthyDocumentIsNotExpired() {
        assertFalse(snap(AllowanceKind.DOCUMENT_VALIDITY, AllowanceStatus.OK).isExpiredDoc)
    }
}
