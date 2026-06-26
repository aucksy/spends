package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.capture.SmsParser.Result
import com.spends.app.domain.model.TxnKind
import org.junit.Test

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
}
