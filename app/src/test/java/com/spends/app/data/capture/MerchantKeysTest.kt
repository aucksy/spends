package com.spends.app.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the merchant-key normalization + matching rules that drive the learned category/note
 * pre-fill. These are golden rules like the SMS parser fixtures: a change that breaks one of
 * these silently breaks "same merchant → same category/note" on real bank alerts.
 */
class MerchantKeysTest {

    // ---- normalize ----

    @Test fun `gateway star prefix is stripped`() =
        assertEquals("amul", MerchantKeys.normalize("RAZ*Amul"))

    @Test fun `company suffixes are stripped`() =
        assertEquals("amul", MerchantKeys.normalize("Amul India Pvt Ltd"))

    @Test fun `glued gateway prefix is stripped`() =
        assertEquals("furlenco", MerchantKeys.normalize("RAZFurlenco"))

    @Test fun `payu prefix and trailing order number are stripped`() =
        assertEquals("swiggy order", MerchantKeys.normalize("PAYU*Swiggy Order 4821"))

    @Test fun `case and punctuation are ignored`() =
        assertEquals("amul retail", MerchantKeys.normalize("AMUL-RETAIL."))

    @Test fun `upi vpa keeps the handle before the at sign`() =
        assertEquals("q123456789", MerchantKeys.normalize("q123456789@ybl"))

    @Test fun `a gateway name alone is kept, not stripped to nothing`() =
        assertEquals("paytm", MerchantKeys.normalize("PAYTM"))

    @Test fun `short brands starting like a gateway are not mangled`() =
        assertEquals("razor", MerchantKeys.normalize("Razor"))

    @Test fun `null blank and symbol-only inputs give no key`() {
        assertNull(MerchantKeys.normalize(null))
        assertNull(MerchantKeys.normalize("   "))
        assertNull(MerchantKeys.normalize("**--**"))
    }

    @Test fun `a full gateway name is never self-mangled`() =
        assertEquals("razorpay", MerchantKeys.normalize("Razorpay"))

    @Test fun `a generic residue after gateway stripping is refused`() {
        assertNull(MerchantKeys.normalize("PAYTM ORDER"))
        assertNull(MerchantKeys.normalize("PHONEPE RECHARGE"))
    }

    @Test fun `a numbers-only residue is refused`() =
        assertNull(MerchantKeys.normalize("UPI POS 12345"))

    @Test fun `an all-generic multi-word key is refused`() =
        assertNull(MerchantKeys.normalize("Card Payment"))

    @Test fun `suffix stripping keeps a short brand - air india`() =
        assertEquals("air", MerchantKeys.normalize("AIR INDIA"))

    // ---- sameMerchant (inputs are normalized keys) ----

    @Test fun `exact keys match`() =
        assertTrue(MerchantKeys.sameMerchant("amul", "amul"))

    @Test fun `word containment matches - amul in amul retail`() =
        assertTrue(MerchantKeys.sameMerchant("amul", "amul retail"))

    @Test fun `word containment matches both directions`() =
        assertTrue(MerchantKeys.sameMerchant("swiggy order", "swiggy"))

    @Test fun `glued single-word prefix matches - swiggy and swiggyinstamart`() =
        assertTrue(MerchantKeys.sameMerchant("swiggy", "swiggyinstamart"))

    @Test fun `different merchants do not match`() {
        assertFalse(MerchantKeys.sameMerchant("zomato", "swiggy"))
        assertFalse(MerchantKeys.sameMerchant("amul", "amazon"))
    }

    @Test fun `a word ending is not a match - cream vs icecream`() =
        assertFalse(MerchantKeys.sameMerchant("cream", "icecream"))

    @Test fun `multi-word substring is not a match - cream stone vs icecream stone`() =
        assertFalse(MerchantKeys.sameMerchant("cream stone", "icecream stone"))

    @Test fun `tiny keys never fuzzy-match`() =
        assertFalse(MerchantKeys.sameMerchant("abc", "abcdef"))

    @Test fun `a lone generic word never claims a longer key`() {
        assertFalse(MerchantKeys.sameMerchant("order", "swiggy order"))
        assertFalse(MerchantKeys.sameMerchant("payment", "rent payment"))
    }

    @Test fun `all-generic words never claim a longer key`() =
        assertFalse(MerchantKeys.sameMerchant("card payment", "sbi card payment"))

    // ---- end to end: the real-world repeats that used to miss ----

    @Test fun `three spellings of the same shop share one memory`() {
        val a = MerchantKeys.normalize("RAZ*AMUL")!!
        val b = MerchantKeys.normalize("Amul India Pvt Ltd")!!
        val c = MerchantKeys.normalize("AMUL RETAIL")!!
        assertTrue(MerchantKeys.sameMerchant(a, b))
        assertTrue(MerchantKeys.sameMerchant(a, c))
        assertTrue(MerchantKeys.sameMerchant(b, c))
    }
}
