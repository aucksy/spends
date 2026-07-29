package com.spends.app.data.ai.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A card must SAY what it is about.
 *
 * The defect this pins shipped for months and looked fine in every test: the cards read "You had a ₹10,000
 * charge, which is 15.4 times the typical ₹650 charge" — arithmetically perfect, and useless, because they
 * never named the category. With several category cards in one carousel it was worse than useless: three
 * cards about three different categories read as three contradictory claims about the same money.
 *
 * The name was in the payload the whole time. The model was told to echo it back as a FIELD so the pairing
 * could be checked, and never told to say it.
 */
class InsightNamesSubjectTest {

    private fun unusual(category: String) = InsightFinding(
        kind = InsightKind.UNUSUAL_CATEGORY,
        category = category,
        amountMinor = 607_000,
        baselineMinor = 200_500,
        multiple = 3.0,
    )

    private fun narrated(title: String, body: String, category: String?) =
        NarratedCard(kind = InsightKind.UNUSUAL_CATEGORY.name, category = category, title = title, body = body)

    @Test
    fun a_card_that_names_its_category_is_kept() {
        val finding = unusual("Dining")
        val cards = InsightNarrator.pair(
            listOf(finding),
            listOf(narrated("Dining is up", "You spent ₹6,070 on Dining, about 3× the usual ₹2,005.", "Dining")),
        )
        assertThat(cards.single().body).contains("Dining")
        assertThat(cards.single().title).isEqualTo("Dining is up")
    }

    /** The exact shape of the shipped defect: correct numbers, no subject. */
    @Test
    fun a_card_that_omits_its_category_falls_back_to_the_template() {
        val finding = unusual("Dining")
        val cards = InsightNarrator.pair(
            listOf(finding),
            listOf(narrated("More Spending", "You spent ₹6070 so far, which is 3 times the usual ₹2005.", "Dining")),
        )
        // Rejected — and the replacement is strictly MORE informative, which is what makes failing closed
        // the right call here rather than a lesser evil.
        assertThat(cards.single().body).isEqualTo(finding.fallbackBody())
        assertThat(cards.single().body).contains("Dining")
    }

    /** Naming it in the heading alone is enough; the pair is what the user reads. */
    @Test
    fun the_name_may_appear_in_the_title_instead_of_the_body() {
        val finding = unusual("Dining")
        val cards = InsightNarrator.pair(
            listOf(finding),
            listOf(narrated("Dining is up", "You spent ₹6,070, about 3× the usual ₹2,005.", "Dining")),
        )
        assertThat(cards.single().title).isEqualTo("Dining is up")
    }

    @Test
    fun matching_ignores_case_but_not_a_renamed_category() {
        val finding = unusual("Food & Drink")
        val kept = InsightNarrator.pair(
            listOf(finding),
            listOf(narrated("Up this cycle", "food & drink is ₹6,070 so far.", "Food & Drink")),
        )
        assertThat(kept.single().body).isEqualTo("food & drink is ₹6,070 so far.")

        // "Food and Drink" is a DIFFERENT string, so the card can no longer be checked against the donut on
        // the same screen. Fails closed to the template rather than showing a category that does not exist.
        val rejected = InsightNarrator.pair(
            listOf(finding),
            listOf(narrated("Up this cycle", "Food and Drink is ₹6,070 so far.", "Food & Drink")),
        )
        assertThat(rejected.single().body).isEqualTo(finding.fallbackBody())
    }

    /** Cards about the whole cycle have no subject to name, and must not be rejected for it. */
    @Test
    fun a_finding_with_no_category_is_unaffected() {
        val pace = InsightFinding(
            kind = InsightKind.PACE,
            amountMinor = 500_000,
            baselineMinor = 300_000,
            days = 12,
        )
        val cards = InsightNarrator.pair(
            listOf(pace),
            listOf(NarratedCard(InsightKind.PACE.name, null, "Running ahead", "Day 12 and ₹5,000 spent.")),
        )
        assertThat(cards.single().body).isEqualTo("Day 12 and ₹5,000 spent.")
    }

    // ---- concentration: several subjects at once ----

    private fun concentration(vararg names: String) = InsightFinding(
        kind = InsightKind.CONCENTRATION,
        amountMinor = 2_943_500,
        sharePercent = 71,
        count = names.size,
        topCategories = names.toList(),
    )

    @Test
    fun a_concentration_card_must_name_every_category_it_lists() {
        val finding = concentration("Rent", "Groceries", "Travel")
        val partial = InsightNarrator.pair(
            listOf(finding),
            listOf(NarratedCard(InsightKind.CONCENTRATION.name, null, "Top Categories", "Rent and Groceries are 71% of spending.")),
        )
        // Two of three named is still a card the user cannot reconcile with the donut.
        assertThat(partial.single().body).isEqualTo(finding.fallbackBody())

        val complete = InsightNarrator.pair(
            listOf(finding),
            listOf(NarratedCard(InsightKind.CONCENTRATION.name, null, "Top Categories", "Rent, Groceries and Travel are 71% of your spending — ₹29,435.")),
        )
        assertThat(complete.single().body).contains("Travel")
    }

    @Test
    fun the_concentration_template_names_the_categories() {
        val body = concentration("Rent", "Groceries", "Travel").fallbackBody()
        assertThat(body).contains("Rent")
        assertThat(body).contains("Groceries")
        assertThat(body).contains("Travel")
        assertThat(body).contains("71%")
        // The old wording, which named nothing, must not come back.
        assertThat(body).doesNotContain("3 categories account")
    }

    /** An older finding with no names still renders rather than producing an empty subject. */
    @Test
    fun a_concentration_without_names_still_reads_sensibly() {
        val finding = InsightFinding(
            kind = InsightKind.CONCENTRATION,
            amountMinor = 2_943_500,
            sharePercent = 71,
            count = 3,
        )
        assertThat(finding.fallbackBody()).contains("3 categories account")
        assertThat(finding.subjectNames()).isEmpty()
    }

    @Test
    fun subject_names_prefer_the_list_then_the_single_category() {
        assertThat(concentration("Rent", "Travel").subjectNames()).containsExactly("Rent", "Travel").inOrder()
        assertThat(unusual("Dining").subjectNames()).containsExactly("Dining")
        assertThat(InsightFinding(kind = InsightKind.PACE).subjectNames()).isEmpty()
    }
}
