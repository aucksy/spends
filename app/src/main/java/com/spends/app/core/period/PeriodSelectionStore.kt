package com.spends.app.core.period

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide holder for the cycle/range the user picked, so the Transactions and Analytics screens
 * stay in sync (#8): change the cycle on one and the other reflects it immediately. In-memory only —
 * it resets to the default on a cold start, which is the expected "fresh launch" behaviour.
 */
@Singleton
class PeriodSelectionStore @Inject constructor() {
    private val _selection = MutableStateFlow(PeriodSelection())
    val selection: StateFlow<PeriodSelection> = _selection.asStateFlow()

    fun set(selection: PeriodSelection) = _selection.update { selection }
}
