package mv.haluvi.serviceconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Calendar

class FaseyhaRulesTest {
    private val ooredooBill = FaseyhaRules.SERVICES.first { it.label == "Ooredoo Bill Pay" }
    private val dhiraaguReload = FaseyhaRules.SERVICES.first { it.label == "Dhiraagu Reload" }
    private val dhiraaguPackages = FaseyhaRules.SERVICES.first { it.label == "Dhiraagu Packages" }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) = Calendar.getInstance().apply {
        clear(); set(year, month, day, hour, minute)
    }.timeInMillis

    @Test fun recipientsAreCleanedPerService() {
        assertEquals("7971234", FaseyhaRules.cleanRecipient(Kind.RECHARGE, "+960 797-1234"))
        assertEquals("7971234", FaseyhaRules.cleanRecipient(Kind.PACKAGE, "9607971234"))
        assertNull(FaseyhaRules.cleanRecipient(Kind.RECHARGE, "79712"))
        assertEquals("SN12345", FaseyhaRules.cleanRecipient(Kind.BILL, "sn 12345"))
        assertNull(FaseyhaRules.cleanRecipient(Kind.BILL, "ab"))
    }

    @Test fun amountsAreValidated() {
        assertEquals(BigDecimal("100.00"), FaseyhaRules.validAmount("100"))
        assertEquals(BigDecimal("12.50"), FaseyhaRules.validAmount("12.5"))
        assertNull(FaseyhaRules.validAmount("0"))
        assertNull(FaseyhaRules.validAmount("1.234"))
        assertNull(FaseyhaRules.validAmount("100001"))
    }

    @Test fun referenceCharactersAreNeverReadAsAmounts() {
        assertEquals(emptyList<BigDecimal>(), FaseyhaRules.amountsIn("Reference FRF6591A2B3C4"))
        assertEquals(listOf(BigDecimal("150.00")), FaseyhaRules.amountsIn("FRF6591A2B3C4 MVR 150.00 Successful"))
        assertEquals(listOf(BigDecimal("1250.50")), FaseyhaRules.amountsIn("Paid 1,250.50 MVR"))
    }

    @Test fun labelledTotalPrefersTotalToPay() {
        val summary = "Order summary Package 5GB Price MVR 100.00 GST MVR 8.00 Total to pay MVR 108.00"
        assertEquals(BigDecimal("108.00"), FaseyhaRules.labelledTotal(summary))
        assertNull(FaseyhaRules.labelledTotal("Order summary Package 5GB"))
    }

    @Test fun recipientMatchingNeedsTheWholeNumber() {
        assertTrue(FaseyhaRules.recipientIn("Account 7971234 Amount MVR 50", "7971234"))
        assertTrue(FaseyhaRules.recipientIn("Number +960 797 1234", "7971234"))
        assertFalse(FaseyhaRules.recipientIn("Account 79712345", "7971234"))
        assertFalse(FaseyhaRules.recipientIn("Rf 50 7971", "7971234"))
    }

    @Test fun providerConfirmationMustRepeatTheReviewedDetails() {
        val good = "Confirm payment Ooredoo / Supernet Account 7971234 Total to pay MVR 250.00 Cancel Confirm"
        assertTrue(FaseyhaRules.confirmationMatches(good, "7971234", "Ooredoo", BigDecimal("250.00")))
        assertFalse(FaseyhaRules.confirmationMatches(good, "7971234", "Ooredoo", BigDecimal("25.00")))
        assertFalse(FaseyhaRules.confirmationMatches(good, "7970000", "Ooredoo", BigDecimal("250.00")))
        assertFalse(FaseyhaRules.confirmationMatches(good, "7971234", "Dhiraagu", BigDecimal("250.00")))
        // Without a labelled total the authorised amount must appear.
        assertTrue(FaseyhaRules.confirmationMatches("Confirm Supernet 7971234 MVR 250 Cancel", "7971234", "Ooredoo", BigDecimal("250")))
    }

    @Test fun ooredooBillRowsUsingSupernetOrPostpaidNamesMatch() {
        assertTrue(FaseyhaRules.kindMatches(Kind.BILL, "Ooredoo Postpaid"))
        assertTrue(FaseyhaRules.kindMatches(Kind.BILL, "Supernet Bill Payment"))
        assertTrue(FaseyhaRules.mentionsOperator("Supernet Bill Payment", "Ooredoo"))
        assertFalse(FaseyhaRules.kindMatches(Kind.RECHARGE, "Dhiraagu Bill Payment"))
        assertTrue(FaseyhaRules.kindMatches(Kind.RECHARGE, "Dhiraagu Reload"))
        assertFalse(FaseyhaRules.kindMatches(Kind.RECHARGE, "Dhiraagu Package Recharge"))
    }

    @Test fun walletDatesParseWithAndWithoutYear() {
        val now = at(2026, Calendar.NOVEMBER, 20, 16, 0)
        assertEquals(at(2026, Calendar.NOVEMBER, 20, 15, 15), FaseyhaRules.parseWalletDate("Nov 20, 3:15 PM", now))
        assertEquals(at(2026, Calendar.NOVEMBER, 20, 15, 15), FaseyhaRules.parseWalletDate("Nov 20 3:15PM", now))
        assertEquals(at(2025, Calendar.DECEMBER, 31, 23, 5), FaseyhaRules.parseWalletDate("Dec 31, 2025, 11:05 PM", now))
        // A date later than now without a year belongs to the previous year.
        assertEquals(at(2025, Calendar.DECEMBER, 30, 9, 0), FaseyhaRules.parseWalletDate("Dec 30, 9:00 AM", now))
        assertNull(FaseyhaRules.parseWalletDate("yesterday", now))
    }

    @Test fun walletRowMustBeRecentSameKindAndSameAmount() {
        val started = at(2026, Calendar.NOVEMBER, 20, 15, 14)
        val now = at(2026, Calendar.NOVEMBER, 20, 15, 30)
        val amount = BigDecimal("250.00")
        assertTrue(FaseyhaRules.walletRowMatches(ooredooBill, "Supernet Bill Payment", "250.00", "Nov 20, 3:15 PM", amount, started, now))
        assertTrue(FaseyhaRules.walletRowMatches(ooredooBill, "Ooredoo Postpaid", "", "Nov 20, 3:15 PM", amount, started, now))
        assertFalse(FaseyhaRules.walletRowMatches(ooredooBill, "Ooredoo Postpaid", "25.00", "Nov 20, 3:15 PM", amount, started, now))
        assertFalse(FaseyhaRules.walletRowMatches(ooredooBill, "Ooredoo Postpaid", "250.00", "Nov 20, 1:00 PM", amount, started, now))
        assertFalse(FaseyhaRules.walletRowMatches(ooredooBill, "Dhiraagu Bill Payment", "250.00", "Nov 20, 3:15 PM", amount, started, now))
        assertFalse(FaseyhaRules.walletRowMatches(dhiraaguReload, "Dhiraagu Bill Payment", "250.00", "Nov 20, 3:15 PM", amount, started, now))
        assertTrue(FaseyhaRules.walletRowMatches(dhiraaguPackages, "Dhiraagu Data Package", "108", "Nov 20, 3:16 PM", BigDecimal("108"), started, now))
    }

    @Test fun receiptMustMatchReferenceRecipientAndAmount() {
        assertTrue(FaseyhaRules.receiptConfirms("FRF123456789", "frf123456789", "7971234", "9607971234", BigDecimal("250"), "250.00"))
        assertFalse(FaseyhaRules.receiptConfirms("FRF123456789", "FRF000000000", "7971234", "7971234", BigDecimal("250"), "250.00"))
        assertFalse(FaseyhaRules.receiptConfirms("FRF123456789", "FRF123456789", "7971234", "7970000", BigDecimal("250"), "250.00"))
        assertFalse(FaseyhaRules.receiptConfirms("FRF123456789", "FRF123456789", "7971234", "7971234", BigDecimal("250"), "25.00"))
    }

    @Test fun newWalletReferenceIdentifiesPaymentWithoutClockMatch() {
        val started = at(2026, Calendar.NOVEMBER, 20, 19, 4)
        val now = at(2026, Calendar.NOVEMBER, 20, 19, 6)
        val amount = BigDecimal("10.00")
        // Phone clock in a different time zone: the time window fails, the new reference still matches.
        assertTrue(FaseyhaRules.walletRowCandidate(ooredooBill, "Ooredoo Bill", "", "Nov 20, 2:04 PM", amount, started, true, now))
        assertFalse(FaseyhaRules.walletRowCandidate(ooredooBill, "Ooredoo Bill", "", "Nov 20, 2:04 PM", amount, started, null, now))
        assertFalse(FaseyhaRules.walletRowCandidate(ooredooBill, "Ooredoo Bill", "", "Nov 20, 7:04 PM", amount, started, false, now))
        assertFalse(FaseyhaRules.walletRowCandidate(ooredooBill, "Ooredoo Bill", "", "Nov 18, 7:04 PM", amount, started, true, now))
        assertTrue(FaseyhaRules.walletRowCandidate(ooredooBill, "Ooredoo Bill", "", "Nov 20, 7:04 PM", amount, started, null, now))
    }

    @Test fun balanceDropMustEqualTheAmount() {
        assertTrue(FaseyhaRules.balanceDropped(BigDecimal("10.80"), BigDecimal("0.80"), BigDecimal("10.00")))
        assertFalse(FaseyhaRules.balanceDropped(BigDecimal("10.80"), BigDecimal("10.80"), BigDecimal("10.00")))
        assertFalse(FaseyhaRules.balanceDropped(null, BigDecimal("0.80"), BigDecimal("10.00")))
    }

    @Test fun faseyhaRechargeConfirmationWithoutCurrencyMarkers() {
        val dialog = "Confirm recharge Please review the details before sending. Operator Ooredoo Raastas Number +960 7651448 " +
            "Recharge amount 11.11 GST (8%) 0.89 Total to pay 12.00 Cancel Confirm & recharge"
        assertTrue(FaseyhaRules.confirmationMatches(dialog, "7651448", "Ooredoo", BigDecimal("12")))
        assertFalse(FaseyhaRules.confirmationMatches(dialog, "7651448", "Ooredoo", BigDecimal("11.11")))
        // The button bar alone is never enough.
        assertFalse(FaseyhaRules.confirmationMatches("Cancel Confirm & recharge", "7651448", "Ooredoo", BigDecimal("12")))
    }
}
