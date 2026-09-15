package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.Holding
import com.cbkres.visavole.data.WorldData
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentTest {

    private val world = WorldData(
        countries = mapOf(
            "FR" to Country("FR", "France"),
            "DE" to Country("DE", "Germany"),
            "AF" to Country("AF", "Afghanistan"),
        ),
        baseline = emptyMap(),
        regimes = emptyList(),
        holdings = mapOf(
            "fr-res" to Holding("fr-res", "France Residence", "residency", "FR"),
            "fr-visa" to Holding("fr-visa", "France Visa", "short_term_visa", "FR"),
        ),
        benefits = emptyMap(),
    )
    private val today = LocalDate.parse("2026-01-01")

    // ---- duplicateSignature: identity behind the "document already exists" guard ----

    @Test fun sameCountrySameDatesIsDuplicate() {
        val a = Document("a", "Passport · GB", DocKind.Passport("GB"), expiry = "2031-05-01", validFrom = "2021-05-01")
        val b = Document("b", "Passport · GB", DocKind.Passport("GB"), expiry = "2031-05-01", validFrom = "2021-05-01")
        assertEquals(a.duplicateSignature(), b.duplicateSignature())
    }

    @Test fun sameCountryDifferentExpiryIsNotDuplicate() {
        val a = Document("a", "Passport · GB", DocKind.Passport("GB"), expiry = "2031-05-01")
        val b = Document("b", "Passport · GB", DocKind.Passport("GB"), expiry = "2035-05-01")
        assertNotEquals(a.duplicateSignature(), b.duplicateSignature())
    }

    @Test fun sameCountryDifferentValidFromIsNotDuplicate() {
        val a = Document("a", "Passport · GB", DocKind.Passport("GB"), validFrom = "2021-05-01")
        val b = Document("b", "Passport · GB", DocKind.Passport("GB"), validFrom = "2022-01-01")
        assertNotEquals(a.duplicateSignature(), b.duplicateSignature())
    }

    @Test fun differentCountryIsNotDuplicate() {
        val a = Document("a", "Passport · GB", DocKind.Passport("GB"))
        val b = Document("b", "Passport · IR", DocKind.Passport("IR"))
        assertNotEquals(a.duplicateSignature(), b.duplicateSignature())
    }

    @Test fun signatureIgnoresIdSoFreshCopyOfSameDocMatches() {
        // The id is deliberately NOT part of the signature: a freshly-added copy (new id) of an
        // existing passport must match the existing one so the guard can block it.
        val existing = Document("id-1", "Passport · GB", DocKind.Passport("GB"))
        val freshCopy = Document("id-2", "Passport · GB", DocKind.Passport("GB"))
        assertEquals(existing.duplicateSignature(), freshCopy.duplicateSignature())
    }

    @Test fun holdingDuplicateKeyedByHoldingIdAndDates() {
        val a = Document("a", "Schengen Visa", DocKind.Holding("schengen-visa"), expiry = "2027-01-01")
        val b = Document("b", "Schengen Visa", DocKind.Holding("schengen-visa"), expiry = "2027-01-01")
        val c = Document("c", "Schengen Visa", DocKind.Holding("schengen-visa"), expiry = "2028-01-01")
        assertEquals(a.duplicateSignature(), b.duplicateSignature())
        assertNotEquals(a.duplicateSignature(), c.duplicateSignature())
    }

    @Test fun customDuplicateNormalisesCountryOrder() {
        val a = Document("a", "residence: FR, DE", DocKind.Custom(setOf("FR", "DE"), kind = "residence"))
        val b = Document("b", "residence: DE, FR", DocKind.Custom(setOf("DE", "FR"), kind = "residence"))
        val c = Document("c", "residence: FR, IT", DocKind.Custom(setOf("FR", "IT"), kind = "residence"))
        assertEquals(a.duplicateSignature(), b.duplicateSignature())
        assertNotEquals(a.duplicateSignature(), c.duplicateSignature())
    }

    // ---- identityKey: what counts as "the same document" for the in-use edit guard ----

    @Test fun passportIdentityIsItsCountry() {
        val a = Document("a", "Passport · GB", DocKind.Passport("GB"))
        val b = Document("b", "Passport · GB", DocKind.Passport("GB"))
        assertEquals(a.identityKey(), b.identityKey())
    }

    @Test fun passportDifferentCountryIsDifferentIdentity() {
        val a = Document("a", "Passport · GB", DocKind.Passport("GB"))
        val b = Document("b", "Passport · FR", DocKind.Passport("FR"))
        assertNotEquals(a.identityKey(), b.identityKey())
    }

    @Test fun passportDatesAndNumberDoNotChangeIdentity() {
        val a = Document("a", "Passport · GB", DocKind.Passport("GB"), countryNumber = "123", expiry = "2031-05-01", validFrom = "2021-05-01")
        val b = Document("b", "Passport · GB", DocKind.Passport("GB"), countryNumber = "456", expiry = "2035-05-01", validFrom = "2022-01-01")
        assertEquals(a.identityKey(), b.identityKey())
    }

    @Test fun passportChangedToVisaIsDifferentIdentity() {
        val a = Document("a", "Passport · FR", DocKind.Passport("FR"))
        val b = Document("b", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa"))
        assertNotEquals(a.identityKey(), b.identityKey())
    }

    @Test fun holdingIdentityIsItsHoldingId() {
        val a = Document("a", "Schengen Visa", DocKind.Holding("schengen-visa"))
        val b = Document("b", "Schengen Visa", DocKind.Holding("schengen-visa"))
        assertEquals(a.identityKey(), b.identityKey())
    }

    @Test fun holdingChangedIsDifferentIdentity() {
        val a = Document("a", "FR Residence", DocKind.Holding("fr-res"))
        val b = Document("b", "FR Visa", DocKind.Holding("fr-visa"))
        assertNotEquals(a.identityKey(), b.identityKey())
    }

    @Test fun customIdentityNormalisesCountryOrder() {
        val a = Document("a", "residence: FR, DE", DocKind.Custom(setOf("FR", "DE"), kind = "residence"))
        val b = Document("b", "residence: DE, FR", DocKind.Custom(setOf("DE", "FR"), kind = "residence"))
        assertEquals(a.identityKey(), b.identityKey())
    }

    @Test fun customEntryTypeDoesNotChangeIdentity() {
        val a = Document("a", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa", entryType = "single"))
        val b = Document("b", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa", entryType = "multiple"))
        assertEquals(a.identityKey(), b.identityKey())
    }

    @Test fun customBlocOrHoldingChangeIsDifferentIdentity() {
        val base = Document("a", "residence: FR", DocKind.Custom(setOf("FR"), kind = "residence"))
        val withBloc = Document("b", "residence: FR", DocKind.Custom(setOf("FR"), blocId = "eu", kind = "residence"))
        val withHolding = Document("c", "residence: FR", DocKind.Custom(setOf("FR"), kind = "residence", holdingId = "fr-res"))
        assertNotEquals(base.identityKey(), withBloc.identityKey())
        assertNotEquals(base.identityKey(), withHolding.identityKey())
    }

    @Test fun customKindChangeIsDifferentIdentity() {
        val a = Document("a", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa"))
        val b = Document("b", "Residence: FR", DocKind.Custom(setOf("FR"), kind = "residence"))
        assertNotEquals(a.identityKey(), b.identityKey())
    }

    // ---- docCategory / coveredCountries ----

    @Test fun passportHasNoCategoryAndCoversItsCountry() {
        val p = Document("p", "Passport · GB", DocKind.Passport("GB"))
        assertNull(p.docCategory(world))
        assertEquals(setOf("GB"), p.coveredCountries(world))
    }

    @Test fun customResidenceCategoryAndCountries() {
        val r = Document("r", "Residence: FR, DE", DocKind.Custom(setOf("FR", "DE"), kind = "residence"))
        assertEquals("residence", r.docCategory(world))
        assertEquals(setOf("FR", "DE"), r.coveredCountries(world))
    }

    @Test fun customVisaCategory() {
        val v = Document("v", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa"))
        assertEquals("visa", v.docCategory(world))
    }

    @Test fun holdingCategoryAndCoverageComeFromWorld() {
        val h = Document("h", "FR Residence", DocKind.Holding("fr-res"))
        assertEquals("residence", h.docCategory(world))
        assertEquals(setOf("FR"), h.coveredCountries(world))
    }

    // ---- conflictsWith: the "one valid visa/residence per country" rule ----

    @Test fun secondResidenceSameCountryIsBlocked() {
        val existing = Document("e", "Residence: AF", DocKind.Custom(setOf("AF"), kind = "residence"), expiry = "2030-01-01")
        val candidate = Document("c", "Residence: AF", DocKind.Custom(setOf("AF"), kind = "residence"), expiry = "2029-01-01")
        assertEquals("e", candidate.conflictsWith(listOf(existing), world, today)?.id)
    }

    @Test fun residenceDifferentCountryIsNotBlocked() {
        val existing = Document("e", "Residence: FR", DocKind.Custom(setOf("FR"), kind = "residence"), expiry = "2030-01-01")
        val candidate = Document("c", "Residence: DE", DocKind.Custom(setOf("DE"), kind = "residence"), expiry = "2029-01-01")
        assertNull(candidate.conflictsWith(listOf(existing), world, today))
    }

    @Test fun visaAndResidenceSameCountryAreNotBlocked() {
        // Same-category rule: a visa and a residence for the same country do not conflict.
        val existing = Document("e", "Residence: FR", DocKind.Custom(setOf("FR"), kind = "residence"), expiry = "2030-01-01")
        val candidate = Document("c", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa"), expiry = "2029-01-01")
        assertNull(candidate.conflictsWith(listOf(existing), world, today))
    }

    @Test fun secondVisaSameCountryIsBlocked() {
        val existing = Document("e", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa"), expiry = "2030-01-01")
        val candidate = Document("c", "Visa: FR", DocKind.Custom(setOf("FR"), kind = "visa"), expiry = "2029-01-01")
        assertEquals("e", candidate.conflictsWith(listOf(existing), world, today)?.id)
    }

    @Test fun expiredExistingDoesNotBlock() {
        val existing = Document("e", "Residence: AF", DocKind.Custom(setOf("AF"), kind = "residence"), expiry = "2020-01-01")
        val candidate = Document("c", "Residence: AF", DocKind.Custom(setOf("AF"), kind = "residence"), expiry = "2029-01-01")
        assertNull(candidate.conflictsWith(listOf(existing), world, today))
    }

    @Test fun passportCandidateNeverConflicts() {
        val existing = Document("e", "Residence: AF", DocKind.Custom(setOf("AF"), kind = "residence"), expiry = "2030-01-01")
        val candidate = Document("c", "Passport · AF", DocKind.Passport("AF"))
        assertNull(candidate.conflictsWith(listOf(existing), world, today))
    }

    @Test fun editingTheSameDocDoesNotSelfConflict() {
        val doc = Document("e", "Residence: AF", DocKind.Custom(setOf("AF"), kind = "residence"), expiry = "2030-01-01")
        val edited = doc.copy(expiry = "2031-01-01") // same id
        assertNull(edited.conflictsWith(listOf(doc), world, today))
    }

    @Test fun holdingResidenceSameCountryIsBlocked() {
        val existing = Document("e", "FR Residence", DocKind.Holding("fr-res"), expiry = "2030-01-01")
        val candidate = Document("c", "FR Residence", DocKind.Holding("fr-res"), expiry = "2029-01-01")
        assertEquals("e", candidate.conflictsWith(listOf(existing), world, today)?.id)
    }

    // ---- passportNumbers / passportCountsByIso: the stable "(N)" marker ----

    @Test fun passportsNumberedByInsertionOrder() {
        val p1 = Document("p1", "Passport · IR", DocKind.Passport("IR"), expiry = "2030-01-01")
        val p2 = Document("p2", "Passport · IR", DocKind.Passport("IR"), expiry = "2031-01-01")
        val p3 = Document("p3", "Passport · IR", DocKind.Passport("IR"), expiry = "2029-01-01")
        assertEquals(mapOf("p1" to 1, "p2" to 2, "p3" to 3), passportNumbers(listOf(p1, p2, p3)))
    }

    @Test fun passportNumbersIndependentPerNationality() {
        val gb1 = Document("gb1", "Passport · GB", DocKind.Passport("GB"))
        val ir1 = Document("ir1", "Passport · IR", DocKind.Passport("IR"))
        val ir2 = Document("ir2", "Passport · IR", DocKind.Passport("IR"))
        assertEquals(mapOf("gb1" to 1, "ir1" to 1, "ir2" to 2), passportNumbers(listOf(gb1, ir1, ir2)))
    }

    @Test fun expiredPassportKeepsItsNumber() {
        val p1 = Document("p1", "Passport · IR", DocKind.Passport("IR"), expiry = "2030-01-01")
        val p2 = Document("p2", "Passport · IR", DocKind.Passport("IR"), expiry = "2020-01-01") // expired
        assertEquals(mapOf("p1" to 1, "p2" to 2), passportNumbers(listOf(p1, p2)))
    }

    @Test fun passportCountsByIso() {
        val gb1 = Document("gb1", "Passport · GB", DocKind.Passport("GB"))
        val ir1 = Document("ir1", "Passport · IR", DocKind.Passport("IR"))
        val ir2 = Document("ir2", "Passport · IR", DocKind.Passport("IR"))
        assertEquals(mapOf("GB" to 1, "IR" to 2), passportCountsByIso(listOf(gb1, ir1, ir2)))
    }
}
