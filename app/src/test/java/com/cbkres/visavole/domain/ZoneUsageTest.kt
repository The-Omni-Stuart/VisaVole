package com.cbkres.visavole.domain

import com.cbkres.visavole.domain.DocKind.Passport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Zone-pooling and gate-cap semantics on the extracted zone math: a zone reached through several
 * stay rules (the CA-4 has one window per country) must give one order-independent answer, and
 * the document cap behind a zone must agree with [DocStatus] about a superseded document.
 */
class ZoneUsageTest {

    private val today = LocalDate.of(2026, 9, 13)

    private val ca4Gt = StayRule(
        "CA-4",
        setOf("GT"),
        "per-entry",
        90,
        null,
        true,
        setOf("*"),
        null,
        id = "ca4-gt",
        zoneId = "ca4",
    )

    private val ca4Sv = StayRule(
        "CA-4",
        setOf("SV"),
        "per-entry",
        180,
        null,
        true,
        setOf("*"),
        null,
        id = "ca4-sv",
        zoneId = "ca4",
    )

    private fun ca4World() = WorldData(
        mapOf(
            "GT" to Country("GT", "Guatemala"),
            "SV" to Country("SV", "El Salvador"),
        ),
        emptyMap(),
        emptyList(),
        emptyMap(),
        emptyMap(),
        listOf(ca4Gt, ca4Sv),
    )

    private fun passport(iso2: String) = Document("p-$iso2", iso2, Passport(iso2))

    private fun zone(calc: TripCalculation) = calc.allowances.first { it.key == "stay:ca4" }

    // Two contiguous stays in one CA-4 trip: Guatemala 2 closed days, El Salvador open through today.
    private val guatemala = TripStop("s1", "GT", today.minusDays(2), today.minusDays(1))
    private val salvador = TripStop("s2", "SV", today, null)

    private fun ca4Trip(vararg stops: TripStop) = listOf(Trip("t1", stops.toList()))

    private fun calculate(stops: List<TripStop>) =
        zone(TripModel.calculate(ca4Trip(*stops.toTypedArray()), listOf(passport("GB")), ca4World(), today))

    @Test
    fun zoneAnswersIdenticallyForBothCa4StopOrders() {
        val addedGtFirst = calculate(listOf(guatemala, salvador))
        val addedSvFirst = calculate(listOf(salvador, guatemala))
        assertEquals(addedSvFirst.totalDays, addedGtFirst.totalDays)
        assertEquals(addedSvFirst.remainingDays, addedGtFirst.remainingDays)
        assertEquals(addedSvFirst.status, addedGtFirst.status)
    }

    @Test
    fun zonePoolsCa4StopsUnderTheMostRestrictiveRule() {
        // Both orders must pool the stays into one entry (3 days) under the tightest window: 90.
        for (stops in listOf(listOf(guatemala, salvador), listOf(salvador, guatemala))) {
            val a = calculate(stops)
            assertEquals(90, a.totalDays)
            assertEquals(87, a.remainingDays)
            assertEquals(3, a.usedDays)
            assertEquals(AllowanceStatus.OK, a.status)
        }
    }

    private fun inUseExhausted() = EntryStatus(1, 1, 0, EntryState.IN_USE, null)

    private fun status(
        reason: ExpiryReason?,
        effectiveExpiry: LocalDate?,
        entries: EntryStatus?,
        expiryDate: LocalDate?,
        replacementValidFrom: LocalDate?,
    ) = DocStatus(false, reason, effectiveExpiry, entries, false, expiryDate, replacementValidFrom)

    @Test
    fun gateCapDateFollowsEffectiveExpiryWhenEntryNotInProgress() {
        val available = status(
            ExpiryReason.DATE, today.plusDays(10),
            EntryStatus(null, 0, null, EntryState.AVAILABLE, null),
            today.plusYears(1), null,
        )
        assertEquals(today.plusDays(10), gateCapDate(available))
        // A passport (no entry accounting) caps on its unified expiry too.
        val passportStatus = status(ExpiryReason.DATE, today.plusDays(30), null, today.plusDays(30), null)
        assertEquals(today.plusDays(30), gateCapDate(passportStatus))
    }

    @Test
    fun inUseGateCapKeepsOwnExpiryThroughEntryExhaustion() {
        // The in-progress-entry exemption: an entry count limits re-entries, not the stay already
        // underway, so exhaustion must not pull the cap into the past mid-stay.
        val s = status(ExpiryReason.ENTRIES, today.minusDays(1), inUseExhausted(), today.plusYears(1), null)
        assertEquals(today.plusYears(1), gateCapDate(s))
    }

    @Test
    fun inUseGateCapHonoursReplacementValidFrom() {
        val s = status(ExpiryReason.SUPERSEDED, today.plusDays(18), inUseExhausted(), today.plusYears(1), today.plusDays(18))
        assertEquals(today.plusDays(18), gateCapDate(s))
    }

    @Test
    fun inUseGateCapsUndatedSupersessionAtItsAnchor() {
        // A replacement without a `validFrom` supersedes immediately: DocStatus anchors it to
        // yesterday, and the gate cap must not keep the stay alive to the old expiry date.
        val s = status(ExpiryReason.SUPERSEDED, today.minusDays(1), inUseExhausted(), today.plusYears(1), null)
        assertEquals(today.minusDays(1), gateCapDate(s))
    }

    // ---- zone gate from the real bundled dataset (the archive-with-expired-passports shape) ----

    private val zoneToday = LocalDate.of(2026, 11, 15)
    private val realWorld = com.cbkres.visavole.data.JdbcRepository.load(java.io.File("src/main/assets/visa_data.db"))
    private val expiredDe = Document("zone-p1", "Expired DE", Passport("DE"), zoneToday.minusDays(1).toString())
    private val liveDe = Document("zone-p2", "Live DE", Passport("DE"), null)

    private fun zoneSnapshotFor(stop: TripStop, docs: List<Document>) =
        TripModel.calculate(
            listOf(Trip("zt", listOf(stop))),
            docs,
            realWorld,
            zoneToday,
        ).allowances.first { it.key == "stay:schengen" }

    @Test
    fun expiredUnreferencedPassportDoesNotCapZoneOnHomeStay() {
        // A closed same-day stay at home declared on the live passport: the archived expired
        // passport is held by nobody's stay today and must not cap the zone into the past.
        val a = zoneSnapshotFor(TripStop("zs1", "DE", zoneToday, zoneToday, documentId = liveDe.id), listOf(expiredDe, liveDe))
        assertEquals(0, a.overstayDays)
        assertEquals(AllowanceStatus.OK, a.status)
    }

    @Test
    fun expiredUnreferencedPassportDoesNotCapZoneOnDeclaredForeignStay() {
        // Same shape with the stay on a zone partner country, still declared on the live passport.
        // A German's FR stay is freedom of movement (0 window days); what is pinned here is that
        // the dormant expired passport must not cap the zone into the past.
        val a = zoneSnapshotFor(TripStop("zs2", "FR", zoneToday.minusDays(2), zoneToday, documentId = liveDe.id), listOf(expiredDe, liveDe))
        assertEquals(0, a.overstayDays)
        assertEquals(AllowanceStatus.OK, a.status)
    }

    @Test
    fun undeclaredZoneStayFallsBackToLiveNotExpiredPassport() {
        // Legacy stop without a document: the fallback gate must weigh the live passport, not an
        // equal-access expired one sitting earlier in the list — that would cap the zone into the past.
        val a = zoneSnapshotFor(TripStop("zs3", "FR", zoneToday.minusDays(1), zoneToday, documentId = null), listOf(expiredDe, liveDe))
        assertEquals(0, a.overstayDays)
        assertEquals(AllowanceStatus.OK, a.status)
    }

    @Test
    fun stayDeclaredOnExpiredPassportStillCapsIntoThePast() {
        // The intended M5 rule stays: a stay actually declared on the lapsed gate is capped into the past.
        val a = zoneSnapshotFor(TripStop("zs4", "FR", zoneToday.minusDays(1), zoneToday, documentId = expiredDe.id), listOf(expiredDe, liveDe))
        assertTrue("a stay on a lapsed declared gate must read as overstay", a.overstayDays > 0)
        assertEquals(AllowanceStatus.DANGER, a.status)
    }
}
