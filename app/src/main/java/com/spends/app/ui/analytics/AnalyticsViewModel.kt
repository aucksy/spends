package com.spends.app.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Totals of active recurring rules in one frequency bucket (cycle-independent). */
data class RecurringFreqSummary(
    val frequency: RecurrenceFreq,
    val outMinor: Long,
    val inMinor: Long,
    val count: Int,
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    recurringRepository: RecurringRepository,
) : ViewModel() {

    /** One row per frequency that has active rules, in Daily→Yearly order. */
    val recurringByFreq: StateFlow<List<RecurringFreqSummary>> = recurringRepository.observeAll()
        .map { rules ->
            val active = rules.filter { it.active }
            RecurrenceFreq.entries.mapNotNull { freq ->
                val inFreq = active.filter { it.frequency == freq }
                if (inFreq.isEmpty()) {
                    null
                } else {
                    RecurringFreqSummary(
                        frequency = freq,
                        outMinor = inFreq.filter { it.kind == TxnKind.EXPENSE }.sumOf { it.amountMinor },
                        inMinor = inFreq.filter { it.kind == TxnKind.INCOME }.sumOf { it.amountMinor },
                        count = inFreq.size,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
