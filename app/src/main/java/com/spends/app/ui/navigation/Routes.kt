package com.spends.app.ui.navigation

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
}
