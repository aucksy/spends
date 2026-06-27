package com.spends.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewRowUi(
    val id: Long,
    val amountMinor: Long,
    val kind: TxnKind,
    val title: String,
    val subtitle: String,
    val categoryId: Long,
    val categoryName: String?,
    val iconKey: String?,
    val colorHex: String?,
    val rawBody: String?, // #10: original SMS body (null for rows scanned before DB v8)
    val sender: String?,
    val receivedAt: Long,
    val searchText: String, // #12: lowercased blob of every searchable field
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val captureRepository: SmsCaptureRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    fun setQuery(q: String) { _query.value = q }

    val items: StateFlow<List<ReviewRowUi>> =
        combine(captureRepository.observePending(), categories, _query) { pending, cats, q ->
            val byId = cats.associateBy { it.id }
            val rows = pending.map { it.toRow(byId) }
            val needle = q.trim().lowercase()
            if (needle.isEmpty()) rows else rows.filter { it.searchText.contains(needle) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The FULL queue size (independent of the search filter) — distinguishes "empty queue" from "no matches". */
    val pendingCount: StateFlow<Int> = captureRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Re-categorise a queued capture in place — review-only, NEVER adds it to the ledger (#9). */
    fun changeCategory(id: Long, categoryId: Long) =
        viewModelScope.launch { captureRepository.setPendingCategory(id, categoryId) }

    fun reject(id: Long) = viewModelScope.launch { captureRepository.rejectPending(id) }

    fun confirmAll() = viewModelScope.launch { captureRepository.confirmAllPending() }

    fun rejectAll() = viewModelScope.launch { captureRepository.clearPending() }

    private fun PendingCaptureEntity.toRow(byId: Map<Long, CategoryEntity>): ReviewRowUi {
        val cat = byId[categoryId]
        val title = merchant?.takeIf { it.isNotBlank() } ?: cat?.name ?: "Detected transaction"
        val instrument = institution?.let { inst -> last4?.let { "$inst ••$it" } ?: inst }
        val subtitle = listOfNotNull(instrument, DateUtils.formatDay(occurredAt)).joinToString(" · ")
        val searchText = listOfNotNull(
            Money.formatRupees(amountMinor), title, subtitle, cat?.name, merchant, institution, last4, sender, rawBody,
        ).joinToString(" ").lowercase()
        return ReviewRowUi(
            id = id,
            amountMinor = amountMinor,
            kind = kind,
            title = title,
            subtitle = subtitle,
            categoryId = categoryId,
            categoryName = cat?.name,
            iconKey = cat?.iconKey,
            colorHex = cat?.colorHex,
            rawBody = rawBody,
            sender = sender,
            receivedAt = receivedAt,
            searchText = searchText,
        )
    }
}
