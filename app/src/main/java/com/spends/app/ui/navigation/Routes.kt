package com.spends.app.ui.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * String routes with compile-time-checked builders. (Full kotlinx-serialization type-safe routes
 * land with the serialization dependency in the backup phase; this keeps Phase 1 dependency-light.)
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val RECURRING = "recurring"
    const val REVIEW = "review"
    const val CAPTURE = "capture"
    const val RESTORE = "restore"

    const val IMPORT = "import"
    const val ARG_FROM_ONBOARDING = "fromOnboarding"

    /** Route to the import flow. When launched from onboarding, finishing it completes onboarding. */
    fun importRoute(fromOnboarding: Boolean = false): String =
        "$IMPORT?$ARG_FROM_ONBOARDING=$fromOnboarding"

    const val IMPORT_PATTERN = "$IMPORT?$ARG_FROM_ONBOARDING={$ARG_FROM_ONBOARDING}"

    const val ADD_EDIT = "add_edit"
    const val ARG_EXPENSE_ID = "expenseId"
    const val NO_EXPENSE_ID = -1L

    /** Route to the add/edit sheet; null id = add a new transaction. */
    fun addEdit(expenseId: Long? = null): String =
        "$ADD_EDIT?$ARG_EXPENSE_ID=${expenseId ?: NO_EXPENSE_ID}"

    const val ADD_EDIT_PATTERN = "$ADD_EDIT?$ARG_EXPENSE_ID={$ARG_EXPENSE_ID}"

    // ---- Per-category transactions drill-down (from Analytics) ----
    const val CATEGORY_TXNS = "category_txns"
    const val ARG_CATEGORY_ID = "categoryId"
    const val ARG_CATEGORY_NAME = "categoryName"
    const val ARG_PERIOD_START = "periodStart"
    const val ARG_PERIOD_END = "periodEnd"

    /**
     * Route to the per-category transaction list for one Analytics period. [name] is URL-encoded so
     * categories with spaces or symbols (e.g. "Food & Drink") survive path/arg parsing.
     */
    fun categoryTxns(categoryId: Long, name: String, startMillis: Long, endExclusiveMillis: Long): String {
        // URLEncoder emits '+' for spaces, but the nav path decoder (Uri.decode) only turns '%20'
        // back into a space — swap so the category title renders cleanly.
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "$CATEGORY_TXNS/$categoryId/$encoded/$startMillis/$endExclusiveMillis"
    }

    const val CATEGORY_TXNS_PATTERN =
        "$CATEGORY_TXNS/{$ARG_CATEGORY_ID}/{$ARG_CATEGORY_NAME}/{$ARG_PERIOD_START}/{$ARG_PERIOD_END}"
}
