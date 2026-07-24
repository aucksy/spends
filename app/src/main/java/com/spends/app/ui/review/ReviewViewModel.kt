package com.spends.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.data.ai.AiCatItem
import com.spends.app.data.ai.AiCategorizer
import com.spends.app.data.ai.GroqClient
import com.spends.app.data.ai.LearnedMerchant
import com.spends.app.data.capture.NotificationCaptureApps
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Review-queue kind filter (#7): show all rows, only expenses, or only income. */
enum class ReviewFilter { ALL, EXPENSE, INCOME }

/** An AI category suggestion for one review row (already mapped to a real, on-list category id). [fromKnown] =
 *  AI recognised this as a variant of a merchant the user already tagged, so the chip reads "Same as before". */
data class ReviewAiSuggestion(
    val categoryId: Long,
    val categoryName: String,
    val cleanName: String?,
    val fromKnown: Boolean = false,
)

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
    val rawBody: String?, // #10: original SMS/notification text (null for rows scanned before DB v8)
    val sender: String?,
    val sourceAppName: String?, // Phase 4: watched-app name for a notification capture, null for SMS rows
    val receivedAt: Long,
    val searchText: String, // #12: lowercased blob of every searchable field
    // AI helper (#1): a category the AI suggests for a row the rules couldn't place. Surfaced ONLY while the
    // row is still on a fallback category ("Other"/"Other Income"); tapping it fills the category (review-only,
    // the user still confirms). Null when AI is off / no suggestion / the row already has a real category.
    val aiSuggestedCategoryId: Long? = null,
    val aiSuggestedCategoryName: String? = null,
    // True when AI matched this to a merchant the user tagged before → the chip reads "Same as before".
    val aiSuggestedFromKnown: Boolean = false,
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val captureRepository: SmsCaptureRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
    private val aiCategorizer: AiCategorizer,
    private val groqClient: GroqClient,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    fun setQuery(q: String) { _query.value = q }

    // #7: show only Income or only Expense in the review queue (or all). Independent of the search query.
    private val _filter = MutableStateFlow(ReviewFilter.ALL)
    val filter: StateFlow<ReviewFilter> = _filter
    fun setFilter(f: ReviewFilter) { _filter.value = f }

    // AI suggestions by pending-row id (#1). Populated by [suggestionCollector]; empty when AI is off (G2).
    private val _suggestions = MutableStateFlow<Map<Long, ReviewAiSuggestion>>(emptyMap())
    // Rows already sent to AI this session (success OR fail) — one attempt per row, never a per-emission retry loop.
    private val requestedIds = HashSet<Long>()

    val items: StateFlow<List<ReviewRowUi>> =
        combine(captureRepository.observePending(), categories, _query, _filter, _suggestions) { pending, cats, q, f, suggestions ->
            val byId = cats.associateBy { it.id }
            val rows = pending.map { it.toRow(byId, suggestions[it.id]) }
            val kindFiltered = when (f) {
                ReviewFilter.ALL -> rows
                ReviewFilter.EXPENSE -> rows.filter { it.kind == TxnKind.EXPENSE }
                ReviewFilter.INCOME -> rows.filter { it.kind == TxnKind.INCOME }
            }
            val needle = q.trim().lowercase()
            if (needle.isEmpty()) kindFiltered else kindFiltered.filter { it.searchText.contains(needle) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The FULL queue size (independent of the search filter) — distinguishes "empty queue" from "no matches". */
    val pendingCount: StateFlow<Int> = captureRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // AI helper (#1): a background side-effect that batches suggestions for eligible rows. It writes NOTHING
        // to the ledger — it only fills the transient [_suggestions] map that surfaces the ✨ chip. Gated on the
        // master switch + the categorize sub-toggle + a key (G2: off → no calls, map cleared).
        viewModelScope.launch { suggestionCollector() }
    }

    private suspend fun suggestionCollector() {
        combine(captureRepository.observePending(), settingsRepository.settings, categories) { pending, s, cats ->
            Triple(pending, s, cats)
        }.collectLatest { (pending, s, cats) ->
            val gateOn = s.aiEnabled && s.aiCategorize && groqClient.hasKey()
            if (!gateOn || cats.isEmpty()) {
                if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyMap()
                requestedIds.clear()
                return@collectLatest
            }
            // Prune state for rows that left the queue.
            val liveIds = pending.mapTo(HashSet()) { it.id }
            _suggestions.value = _suggestions.value.filterKeys { it in liveIds }
            requestedIds.retainAll(liveIds)

            val names = cats.map { it.name }
            // One learned-table read for the whole batch (not one per row).
            val isLearned = captureRepository.learnedMerchantPredicate()
            // Eligible = a real merchant string + the rules landed on a fallback ("Other"/"Other Income", i.e.
            // no confident rule match) + NO learned mapping (G1: learned always wins, AI is never consulted for
            // a taught merchant) + not already attempted this session.
            val eligible = ArrayList<PendingCaptureEntity>()
            for (p in pending) {
                if (p.id in requestedIds) continue
                val merchant = p.merchant
                if (merchant.isNullOrBlank()) continue
                if (!isFallbackCategory(cats.firstOrNull { it.id == p.categoryId }?.name)) continue
                if (isLearned(merchant)) continue
                eligible.add(p)
            }
            if (eligible.isEmpty()) return@collectLatest

            // Only now (there's work) read the learned shortcuts, sent to AI so it can reproduce a prior choice
            // for a spelling variant the deterministic matcher missed (enhances the learned memory, never overrides).
            val learned = captureRepository.learnedCategoryPairs().map { LearnedMerchant(it.first, it.second) }

            eligible.chunked(BATCH_SIZE).forEach { batch ->
                val batchIds = batch.map { it.id }
                requestedIds.addAll(batchIds) // one attempt per row this session (marked up front)
                try {
                    val batchItems = batch.map { AiCatItem(it.id, it.merchant!!.trim(), it.kind) }
                    val result = aiCategorizer.suggest(batchItems, names, learned)
                    if (result.isNotEmpty()) {
                        val mapped = result.mapNotNull { (id, sug) ->
                            val catId = cats.firstOrNull { it.name.equals(sug.categoryName, ignoreCase = true) }?.id
                            if (catId == null) null else id to ReviewAiSuggestion(catId, sug.categoryName, sug.cleanName, sug.fromKnown)
                        }.toMap()
                        if (mapped.isNotEmpty()) _suggestions.value = _suggestions.value + mapped
                    }
                } catch (c: CancellationException) {
                    // A new queue emission cancelled us mid-call (e.g. a scan burst): un-mark so these rows are
                    // retried next time rather than silently losing their chip for the session. Then honour cancel.
                    requestedIds.removeAll(batchIds.toSet())
                    throw c
                }
            }
        }
    }

    /** Accept the AI's suggestion for a row (#1): review-only — fills the pending row's category (and learns
     *  it, since tapping is a deliberate pick). NEVER writes to the ledger; the user still confirms via
     *  "Review and Add" / "Add all". */
    fun acceptSuggestion(id: Long, categoryId: Long) = viewModelScope.launch {
        captureRepository.setPendingCategory(id, categoryId)
        _suggestions.value = _suggestions.value - id
    }

    /** Re-categorise a queued capture in place — review-only, NEVER adds it to the ledger (#9). */
    fun changeCategory(id: Long, categoryId: Long) =
        viewModelScope.launch { captureRepository.setPendingCategory(id, categoryId) }

    fun reject(id: Long) = viewModelScope.launch { captureRepository.rejectPending(id) }

    fun confirmAll() = viewModelScope.launch { captureRepository.confirmAllPending() }

    fun rejectAll() = viewModelScope.launch { captureRepository.clearPending() }

    private fun PendingCaptureEntity.toRow(byId: Map<Long, CategoryEntity>, suggestion: ReviewAiSuggestion?): ReviewRowUi {
        val cat = byId[categoryId]
        val title = merchant?.takeIf { it.isNotBlank() } ?: cat?.name ?: "Detected transaction"
        val instrument = institution?.let { inst -> last4?.let { "$inst ••$it" } ?: inst }
        val subtitle = listOfNotNull(instrument, DateUtils.formatDay(occurredAt)).joinToString(" · ")
        val sourceAppName = sourceApp?.let { NotificationCaptureApps.displayName(it) ?: it }
        val searchText = listOfNotNull(
            Money.formatRupees(amountMinor), title, subtitle, cat?.name, merchant, institution, last4, sender, rawBody,
            sourceAppName,
        ).joinToString(" ").lowercase()
        // Only surface the chip while the row is still on a fallback category — once the user (or the accept)
        // sets a real category, the chip disappears even if a stale suggestion lingers in the map.
        val showSuggestion = suggestion != null && isFallbackCategory(cat?.name)
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
            sourceAppName = sourceAppName,
            receivedAt = receivedAt,
            searchText = searchText,
            aiSuggestedCategoryId = if (showSuggestion) suggestion!!.categoryId else null,
            aiSuggestedCategoryName = if (showSuggestion) suggestion!!.categoryName else null,
            aiSuggestedFromKnown = showSuggestion && suggestion!!.fromKnown,
        )
    }

    private companion object {
        const val BATCH_SIZE = 25
    }
}

/** A row the deterministic rules couldn't place ("Other"/"Other Income"/none) — the ONLY rows AI may suggest
 *  for (a confident rule or learned pick lands on a real category and is never touched). Top-level + testable. */
internal fun isFallbackCategory(name: String?): Boolean =
    name == null || name.equals("Other", ignoreCase = true) || name.equals("Other Income", ignoreCase = true)
