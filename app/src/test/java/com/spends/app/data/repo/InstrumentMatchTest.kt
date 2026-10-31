package com.spends.app.data.repo

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.capture.Institution
import com.spends.app.data.capture.InstitutionType
import com.spends.app.data.capture.SmsParser
import com.spends.app.data.capture.SmsParser.Channel
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.domain.model.PaymentMethodType
import org.junit.Test

/**
 * IDFC sends credit-card AND savings-account alerts from one sender, so the bank name alone cannot say which
 * paid. A card spend whose last4 matched nothing was pre-filled with the IDFC account ("it always thinks it
 * is the IDFC bank"). The fallback must respect what the alert says paid. Bodies are masked real shapes.
 */
class InstrumentMatchTest {

    private val now = 1_700_000_000_000L

    private fun pm(id: Long, type: PaymentMethodType, label: String, institution: String?) = PaymentMethodEntity(
        id = id, type = type, label = label, institution = institution, colorHex = "#000000",
        firstSeenAt = 0, lastActivityAt = 0,
    )

    private val idfcAccount = pm(1, PaymentMethodType.BANK_ACCOUNT, "IDFC Savings", "IDFC First Bank")
    private val idfcCard = pm(2, PaymentMethodType.CREDIT_CARD, "IDFC RuPay", "IDFC FIRST")

    private val cardAlert = "INR 250.00 spent on your IDFC FIRST Bank Credit Card ending XX1234 at M41ABCDEF on " +
        "05-OCT-2026 at 08:15 PM. Avbl Limit: INR 98,765.00. If not you, call 1800XXXXXXX"
    private val accountAlert = "Your A/C XXXXX123456 is debited by INR 250.00 on 05/10/26 for UPI txn to " +
        "SOMESHOP. New Bal: INR 10,000.00 - IDFC FIRST Bank"

    @Test fun cardAlertIsCreditCardChannel() {
        assertThat(SmsParser.parse("JK-IDFCFB", cardAlert, now).channel).isEqualTo(Channel.CREDIT_CARD)
    }

    @Test fun accountAlertIsAccountChannel() {
        assertThat(SmsParser.parse("JK-IDFCFB", accountAlert, now).channel).isEqualTo(Channel.ACCOUNT)
    }

    @Test fun cardOnlySenderIsCreditCardChannel() {
        val body = "Rs.499.00 spent on your SBI Credit Card ending 1234 at AMAZON on 05/10/26. Trxn. not done by you? Report at https://sbicard.com/Dispute"
        assertThat(SmsParser.parse("VM-SBICRD", body, now).channel).isEqualTo(Channel.CREDIT_CARD)
    }

    @Test fun bankAndCardSenderAccountAlertIsAccount() {
        // ICICI's sender is typed CREDIT_CARD, but this is its savings account.
        val body = "ICICI Bank Acct XX123 debited for Rs 250.00 on 05-Oct-26; SOMESHOP credited. UPI:123456789012."
        assertThat(SmsParser.channelOf(body.lowercase(), Institution("ICICI Bank", InstitutionType.CREDIT_CARD))).isEqualTo(Channel.ACCOUNT)
    }

    @Test fun cardBillPaidFromAccountIsAccount() {
        val body = "Rs 5,000.00 debited from A/c XX1234 on 05-Oct-26 towards Credit Card payment. -IDFC FIRST Bank"
        assertThat(SmsParser.channelOf(body.lowercase(), Institution("IDFC First Bank", InstitutionType.BANK))).isEqualTo(Channel.ACCOUNT)
    }

    @Test fun cardSpendPicksTheCardNotTheAccount() {
        val got = PaymentMethodRepository.pickByInstitution(listOf(idfcAccount, idfcCard), "IDFC First Bank", Channel.CREDIT_CARD)
        assertThat(got).isEqualTo(idfcCard.id)
    }

    @Test fun accountSpendPicksTheAccountNotTheCard() {
        val got = PaymentMethodRepository.pickByInstitution(listOf(idfcAccount, idfcCard), "IDFC First Bank", Channel.ACCOUNT)
        assertThat(got).isEqualTo(idfcAccount.id)
    }

    @Test fun cardSpendNeverFallsBackToTheAccount() {
        // The exact old failure: only the account is saved, so it was the single "IDFC" instrument.
        val got = PaymentMethodRepository.pickByInstitution(listOf(idfcAccount), "IDFC First Bank", Channel.CREDIT_CARD)
        assertThat(got).isNull()
    }

    @Test fun unknownChannelWithTwoCandidatesStaysAmbiguous() {
        val got = PaymentMethodRepository.pickByInstitution(listOf(idfcAccount, idfcCard), "IDFC First Bank", Channel.UNKNOWN)
        assertThat(got).isNull()
    }

    @Test fun cardSavedWithoutInstitutionMatchesOnItsLabel() {
        val typed = pm(3, PaymentMethodType.CREDIT_CARD, "IDFC RuPay", null)
        val got = PaymentMethodRepository.pickByInstitution(listOf(idfcAccount, typed), "IDFC First Bank", Channel.CREDIT_CARD)
        assertThat(got).isEqualTo(typed.id)
    }

    @Test fun otherBanksDoNotMatch() {
        val hdfc = pm(4, PaymentMethodType.CREDIT_CARD, "HDFC Millennia", "HDFC Bank")
        val got = PaymentMethodRepository.pickByInstitution(listOf(hdfc), "IDFC First Bank", Channel.CREDIT_CARD)
        assertThat(got).isNull()
    }

    @Test fun issuerKeyIgnoresGenericWords() {
        assertThat(PaymentMethodRepository.issuerKey("IDFC First Bank")).isEqualTo("idfc")
        assertThat(PaymentMethodRepository.issuerKey("The State Bank of India")).isEqualTo("state")
        assertThat(PaymentMethodRepository.issuerKey("SBI Card")).isEqualTo("sbi")
        assertThat(PaymentMethodRepository.issuerKey("Bank")).isNull()
        assertThat(PaymentMethodRepository.issuerKey(null)).isNull()
    }

    @Test fun discoveredBankCardIsNamedACard() {
        assertThat(PaymentMethodRepository.defaultCardLabel("IDFC First Bank")).isEqualTo("IDFC First Bank Credit Card")
        assertThat(PaymentMethodRepository.defaultCardLabel("SBI Card")).isEqualTo("SBI Card")
    }
}
