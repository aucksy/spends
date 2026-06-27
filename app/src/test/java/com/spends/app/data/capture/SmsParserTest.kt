package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsParser.Result
import com.spends.app.domain.model.TxnKind
import org.junit.Test
import java.time.LocalDate

/**
 * Golden fixtures from PRD parser-fixtures-and-allowlist.md (§D) — all masked. Every `txn` must
 * produce its amount + kind, every `statement` must classify as STATEMENT, every control as IGNORED.
 */
class SmsParserTest {

    private val now = 1_700_000_000_000L
    private fun p(sender: String, body: String) = SmsParser.parse(sender, body, now)

    // ---- IDFC First Bank (account) ----
    @Test fun idfc_debit() {
        val r = p("JK-IDFCFB", "Your A/C XX1234 is debited by INR 850.00 on 21/06/2026 14:32. New Bal :INR 12,400.50. Team IDFC FIRST Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(85000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("1234")
    }

    @Test fun idfc_credit_income() {
        val r = p("JK-IDFCFB", "Your A/C XX1234 is credited with INR 75,000.00 on 01/06/2026 10:05. Your new balance is INR 88,000.00. Team IDFC FIRST Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(7500000)
        assertThat(r.kind).isEqualTo(TxnKind.INCOME)
    }

    @Test fun idfc_upi_p2p_debit() {
        val r = p("AD-IDFCFB", "Your A/c XX1234 debited by Rs. 1,200.00 on 21/06/2026; <PERSON> credited. RRN 0000. Available balance Rs. 9,000.00. Team IDFC FIRST Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(120000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    // ---- Axis Bank (account) ----
    @Test fun axis_upi_debit() {
        val r = p("AD-AXISBK-S", "INR 499.00 debited A/c no. XX5678 21-06-2026 13:01:22 UPI/P2A/000000/<PAYEE> Not you? SMS BLOCKUPI Cust ID to Axis Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(49900)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("5678")
    }

    @Test fun axis_nach_emi() {
        val r = p("AD-AXISBK", "NACH debit towards L&TFINANCELTD for INR 18,500.00 with UMRN UTIB0000 has been successfully processed in A/c no. XX5678 today - Axis Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(1850000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.categoryHint).isEqualTo("Loan/EMI")
    }

    // ---- SBI Card ----
    @Test fun sbi_card_spend() {
        val r = p("VM-SBICRD", "Rs.640.00 spent on your SBI Credit Card ending 1234 at <MERCHANT> on 21/06/26. Trxn. not done by you? Report at https://sbicard.com/Dispute")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(64000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("1234")
    }

    @Test fun sbi_limit_alert_ignored() {
        val r = p("VM-SBICRD", "Alert! You have consumed 80% Credit Limit. Balance Limit: Rs.20,000.00 T&C-SBI Card")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    // ---- IndusInd ----
    @Test fun indus_spend() {
        val r = p("VM-INDUSB", "INR 1,150.00 spent on IndusInd Card XX1234 on 21-06-2026 19:40:10 pm at <MERCHANT>. Avl Lmt: INR 50,000.00.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(115000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun indus_statement() {
        val r = p("VM-INDUSB", "21/06/26 Stmt Alert: Total Amount Due on your IndusInd Bank Credit Card XX1234 is INR 22,000.00 and Minimum Amount Due is INR 1,100.00, payable by 09/07/26.")
        assertThat(r.result).isEqualTo(Result.STATEMENT)
    }

    @Test fun indus_bill_payment_transfer() {
        val r = p("VM-INDUSB", "Dear Customer, thank you for your Payment of INR 22,000.00 towards your IndusInd Bank Credit Card on 05/07/26 - IndusInd Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.kind).isEqualTo(TxnKind.TRANSFER)
    }

    // ---- ICICI ----
    @Test fun icici_spend_using() {
        val r = p("VK-ICICIT", "INR 999.00 spent using ICICI Bank Card XX1234 on 21-Jun-26 on <MERCHANT>. Avl Limit: INR 60,000.00.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(99900)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun icici_bbps_payment_transfer() {
        val r = p("AX-ICICIB", "Payment of Rs 5,000.00 has been received on your ICICI Bank Credit Card XX1234 through Bharat Bill Payment System on 21-JUN-26.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.kind).isEqualTo(TxnKind.TRANSFER)
    }

    // ---- OneCard / BOB ----
    @Test fun onecard_purchase() {
        val r = p("BP-ONECRD", "Your payment of Rs. 320.00 at <MERCHANT> has been processed on card ending XX1234. Check details -Team OneCard")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(32000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun onecard_bill_generated_statement() {
        val r = p("VM-BOBONE", "Hi <NAME>, Your bill has been generated. Pay your bill of Rs. 14,000.00 here - BOBCARD OneCard")
        assertThat(r.result).isEqualTo(Result.STATEMENT)
    }

    @Test fun onecard_declined_ignored() {
        val r = p("VM-BOBONE", "Sorry, we declined your txn of Rs.2,000.00 at <MERCHANT> -Team OneCard")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    // ---- Yes Bank ----
    @Test fun yes_spend() {
        val r = p("JD-YESBNK", "INR 780.00 spent on YES BANK Card X1234 @<MERCHANT> 21-06-26 18:22:05 pm. Avl Lmt INR 40,000.00.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(78000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun yes_due_statement() {
        val r = p("JD-YESBNK", "Payment of Credit Card X1234 is due on 28/06/26. Min due Rs.1,000.00. Total Due Rs.20,000.00. Ignore if paid-YES BANK")
        assertThat(r.result).isEqualTo(Result.STATEMENT)
    }

    // ---- Amex (last 5) ----
    @Test fun amex_spend_last5() {
        val r = p("AD-AMEXIN", "Alert: You've spent INR 2,499.00 on your AMEX card ** 12345 at <MERCHANT> on 21 June 26 at 07:15 PM IST.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(249900)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("12345")
    }

    @Test fun amex_payment_received_transfer() {
        val r = p("AD-AMEXIN", "Dear Customer, a payment of INR 30,000.00 was received on your Amex Card ***12345 21/06/26. Thank you.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.kind).isEqualTo(TxnKind.TRANSFER)
    }

    // ---- RBL ----
    @Test fun rbl_spend_parens() {
        val r = p("VM-RBLCRD", "INR350.00 spent at <MERCHANT> on RBL Bank credit card (1234) on 21-06-26. AVL limit- INR45,000.00.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(35000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("1234")
    }

    @Test fun rbl_promo_ignored() {
        val r = p("VM-RBLCRD", "Hi! Click to explore the pre-approved Card Upgrade facility on your RBL Bank Credit Card(1234). T&Cs apply.")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    // ---- HDFC ----
    @Test fun hdfc_spend() {
        val r = p("AD-HDFCBK", "Spent Rs.560.00 On HDFC Bank Card 1234 At <MERCHANT> On 2026-06-21:11:20:33. Not You? To Block+Reissue Call")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(56000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("1234")
    }

    // ---- PNB ----
    @Test fun pnb_payment_received_transfer() {
        val r = p("VM-PNBCCD", "Thank you Rs.4,000.00/- has been received as payment towards your PNB credit card 1234 via Online Payment. Your available credit limit is Rs.50,000.00. - PNB")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.kind).isEqualTo(TxnKind.TRANSFER)
    }

    // ---- Paytm / SBI-UPI / L&T ----
    @Test fun paytm_wallet_debit() {
        val r = p("VM-IPAYTM", "Paid Rs. 240.00 to <MERCHANT> from Paytm Balance. Updated Balance: Paytm Wallet- Rs 0.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(24000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun paytm_idfc_bill_statement() {
        val r = p("VM-IPAYTM", "Bill generated for IDFC Bank Credit Card 1234. Total Due Rs 9,000.00 and due on 12th Sep 26. Pay now")
        assertThat(r.result).isEqualTo(Result.STATEMENT)
    }

    @Test fun sbi_upi_credit_income() {
        val r = p("AD-SBIUPI", "Dear SBI UPI User, ur A/cX1234 credited by Rs500.00 on 21Jun26 by (Ref no 000000)")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(50000)
        assertThat(r.kind).isEqualTo(TxnKind.INCOME)
    }

    @Test fun lnt_loan_confirmation() {
        val r = p("VM-LNTFIN", "Dear Customer, Thank you for payment of Rs.18,500.00/- towards the loan account number H0000. Regards, L&T Finance Ltd.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.categoryHint).isEqualTo("Loan/EMI")
    }

    // ---- Controls ----
    @Test fun otp_ignored() {
        assertThat(p("VM-SBICRD", "123456 is your OTP for transaction. Do not share with anyone.").result).isEqualTo(Result.IGNORED)
    }

    @Test fun promo_ignored() {
        assertThat(p("AD-HDFCBK", "Get a pre-approved personal loan up to Rs.5,00,000! Click here.").result).isEqualTo(Result.IGNORED)
    }

    @Test fun unknown_sender_ignored() {
        assertThat(p("AD-JIOFIB", "Your bill is due. Total amount payable : Rs.1178.82.").result).isEqualTo(Result.IGNORED)
    }

    @Test fun future_mandate_ignored() {
        val r = p("VM-LNTFIN", "Dear Customer, EMI debit of Rs.5000/- against loan account number H123 will be processed on 25-SEP-2026. Please maintain sufficient balance.")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    // ---- EMI-conversion notices (#1): NOT a fresh money movement — must NOT be queued ----

    /** Regression guard for the reported bug: a conversion notice that ALSO carries a spend verb. */
    @Test fun emi_conversion_with_spend_ignored() {
        val r = p("VM-INDUSB", "INR 49,993.00 spent on IndusInd Card XX1234 at <MERCHANT> has been converted into 6 EMIs of INR 8,500.00 each. -IndusInd Bank")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    @Test fun emi_conversion_offer_ignored() {
        val r = p("VM-SBICRD", "Convert your recent transaction of Rs.12,000.00 to EMI at low interest. Click to avail EMI now. -SBI Card")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    @Test fun emi_conversion_request_ignored() {
        val r = p("VM-INDUSB", "Your EMI conversion request for INR 49,993.00 on Card XX1234 has been processed successfully. -IndusInd Bank")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    /** "Spend → no-cost EMI" promo that contains the spend verb 'purchase' must still be IGNORED. */
    @Test fun emi_avail_offer_ignored() {
        val r = p("AD-HDFCBK", "Avail EMI on your recent purchase of Rs.15,000.00 on HDFC Bank Card 1234. No-cost EMI available. T&C apply.")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    /** Boundary lock: a GENUINE EMI installment auto-debit (no convert/avail tokens) STILL logs as expense. */
    @Test fun emi_installment_debit_txn() {
        val r = p("VM-LNTFIN", "Your EMI of Rs.5,000.00 has been debited from A/c XX1234 towards loan account H123 on 21-06-2026. -L&T Finance")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.categoryHint).isEqualTo("Loan/EMI")
    }

    /**
     * Over-rejection guard: "premium"/"academic" contain the substring "emi", so a raw substring check +
     * a "convert" token would WRONGLY drop this real debit. The word-boundary anchor must keep it logging.
     */
    @Test fun premium_debit_with_convert_word_txn() {
        val r = p("AD-AXISBK", "INR 12,000.00 debited A/c no. XX5678 for insurance premium, converted to annual mode. -Axis Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.categoryHint).isEqualTo("Investments")
    }

    /** IndusInd EMI-conversion OFFER: carries the spend verb but is NOT a fresh debit — must IGNORE (#10). */
    @Test fun indus_split_spend_into_emis_ignored() {
        val r = p("VM-INDUSB", "Split your INR 49,993.00 spend at <MERCHANT> into EMIs. Convert now: https://bank.example/x - IndusInd Bank")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    @Test fun indus_split_outstanding_easy_emis_ignored() {
        val r = p("VM-INDUSB", "Split INR 5,59,393.44 Credit Card outstanding into Easy EMIs at: https://bank.example/y - IndusInd Bank")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    // ---- #5: a GENUINE point-of-sale purchase that carries a promotional EMI-conversion FOOTER must STILL
    // be captured. These are the real spends the round-5 EMI filter started silently dropping. ----
    @Test fun sbi_card_spend_with_convert_emi_footer_txn() {
        val r = p("VM-SBICRD", "Rs.3168.00 spent on your SBI Credit Card ending 1234 at RAZ*Amul on 26/06/26. Convert to EMI: SMS EMI to 567676 or visit sbicard.com. Avl Lmt Rs.45,000.00")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(316800)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("1234")
    }

    @Test fun sbi_card_spend_with_easy_emi_footer_txn() {
        val r = p("VM-SBICRD", "Rs.588.53 spent on your SBI Credit Card ending 1234 at RAZ*Furlenco on 25/06/26. Split into easy EMIs at sbicard.com/emi. Not you? Report now.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(58853)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    // The SBI/HDFC footer literally reads "Convert INTO EMI(s)" (not just "to EMI") — a genuine purchase
    // must survive it (isPromo must not short-circuit, and the EMI detector must be fresh-spend-guarded).
    @Test fun sbi_card_spend_with_convert_into_emi_footer_txn() {
        val r = p("VM-SBICRD", "Rs.3168.00 spent on your SBI Credit Card ending 1234 at RAZ*Amul on 26/06/26. Convert into EMI: SMS EMI to 567676.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(316800)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun hdfc_card_spend_with_convert_into_emis_footer_txn() {
        val r = p("AD-HDFCBK", "Rs.25000.00 spent on HDFC Bank Card 1234 at CROMA on 21-06-26. Convert into EMIs now, call 18002586161.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(2500000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    // ICICI introduces the merchant with "on <MERCHANT>" (not "at"); a genuine spend with an "EMI option
    // available" footer must still be captured.
    @Test fun icici_on_merchant_spend_with_emi_option_footer_txn() {
        val r = p("VK-ICICIT", "INR 30000.00 spent using ICICI Bank Card XX1234 on 21-Jun-26 on AMAZON. EMI option available on this transaction. Avl Limit: INR 60000.00.")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(3000000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }

    // The two EXACT June-2026 SBI Card alerts the user reported as missed (verbatim bodies). These parse
    // fine — proof the parser is NOT why they were dropped (they arrived as RCS "Business Chat", which the
    // SMS scan can't read). Locked in so the parser never regresses on this real format.
    @Test fun sbi_card_spend_razamul_txn() {
        val r = p("VM-SBICRD", "Rs.3,168.00 spent on your SBI Credit Card ending 0436 at RAZAmul on 25/06/26. Trxn. not done by you? Report at https://sbicard.com/Dispute")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(316800)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("0436")
    }

    @Test fun sbi_card_emandate_debit_razfurlenco_txn() {
        val r = p("VM-SBICRD", "Transaction of Rs.588.53 at RAZFurlenco against E-mandate (SiHub ID - XnmzrilzlS) registered by you at merchant has been debited to your SBI Credit Card ending 0436 on 25-06-26. To manage E-mandate, click: http://www.sbicard.com/emandates")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(58853)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(r.last4).isEqualTo("0436")
    }

    // ---- #6: bank SMS carry only a date (no clock time); the transaction must take the SMS's actual
    // arrival time, not a hardcoded noon, so a day's spends keep their real order. ----
    @Test fun occurred_at_uses_sms_arrival_time_not_noon() {
        val received = DateUtils.epochMillisFor(LocalDate.of(2026, 6, 26), 9, 41)
        val r = SmsParser.parse("VM-SBICRD", "Rs.640.00 spent on your SBI Credit Card ending 1234 at SHOP on 26/06/26.", received)
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.occurredAt).isEqualTo(received)
    }

    @Test fun occurred_at_earlier_body_date_carries_sms_time() {
        // Delivered a day late: body says the 24th but the SMS arrived on the 26th at 9:41 — keep the 24th,
        // at the SMS's time-of-day (never noon).
        val received = DateUtils.epochMillisFor(LocalDate.of(2026, 6, 26), 9, 41)
        val r = SmsParser.parse("VM-SBICRD", "Rs.640.00 spent on your SBI Credit Card ending 1234 at SHOP on 24/06/26.", received)
        assertThat(r.occurredAt).isEqualTo(DateUtils.epochMillisFor(LocalDate.of(2026, 6, 24), 9, 41))
    }

    // ---- #9: bank EMI OFFERS using the phrases the user named ("convert to EMI", "EMI conversion",
    // "split your transaction") must be IGNORED; a genuine purchase that merely carries such a FOOTER is
    // still captured. ----
    @Test fun convert_to_emi_offer_ignored() {
        val r = p("VM-SBICRD", "Convert to EMI your recent spends! Get up to Rs.50,000.00 as EMI on your SBI Card. Avail now at sbicard.com.")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    @Test fun split_your_transaction_offer_ignored() {
        val r = p("AD-HDFCBK", "Split your transaction into easy EMIs! Convert your spend of Rs.10,000.00 on HDFC Bank Card at low interest. T&C apply.")
        assertThat(r.result).isEqualTo(Result.IGNORED)
    }

    /** Boundary guard for the new phrase: a GENUINE point-of-sale spend that carries a "split your
     *  transaction" footer must STILL be captured (the fresh-spend rule wins over the EMI footer). */
    @Test fun spend_with_split_your_transaction_footer_txn() {
        val r = p("AD-HDFCBK", "Rs.2,500.00 spent on HDFC Bank Card XX1234 at AMAZON on 21/06/26. You can split your transaction into easy EMIs at hdfcbank.com. -HDFC Bank")
        assertThat(r.result).isEqualTo(Result.TRANSACTION)
        assertThat(r.amountMinor).isEqualTo(250000)
        assertThat(r.kind).isEqualTo(TxnKind.EXPENSE)
    }
}
