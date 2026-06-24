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
    const val IMPORT = "import"

    const val ADD_EDIT = "add_edit"
    const val ARG_EXPENSE_ID = "expenseId"
    const val NO_EXPENSE_ID = -1L

    /** Route to the add/edit sheet; null id = add a new transaction. */
    fun addEdit(expenseId: Long? = null): String =
        "$ADD_EDIT?$ARG_EXPENSE_ID=${expenseId ?: NO_EXPENSE_ID}"

    const val ADD_EDIT_PATTERN = "$ADD_EDIT?$ARG_EXPENSE_ID={$ARG_EXPENSE_ID}"
}
