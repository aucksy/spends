package com.spends.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
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
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val captureRepository: SmsCaptureRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<ReviewRowUi>> =
        combine(captureRepository.observePending(), categories) { pending, cats ->
            val byId = cats.associateBy { it.id }
            pending.map { it.toRow(byId) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(id: Long) = viewModelScope.launch { captureRepository.confirmPending(id) }

    fun changeCategoryAndConfirm(id: Long, categoryId: Long) =
        viewModelScope.launch { captureRepository.confirmPending(id, categoryId) }

    fun reject(id: Long) = viewModelScope.launch { captureRepository.rejectPending(id) }

    fun confirmAll() = viewModelScope.launch { captureRepository.confirmAllPending() }

    fun rejectAll() = viewModelScope.launch { captureRepository.clearPending() }

    private fun PendingCaptureEntity.toRow(byId: Map<Long, CategoryEntity>): ReviewRowUi {
        val cat = byId[categoryId]
        val title = merchant?.takeIf { it.isNotBlank() } ?: cat?.name ?: "Captured transaction"
        val instrument = institution?.let { inst -> last4?.let { "$inst ••$it" } ?: inst }
        val subtitle = listOfNotNull(instrument, DateUtils.formatDay(occurredAt)).joinToString(" · ")
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
        )
    }
}
