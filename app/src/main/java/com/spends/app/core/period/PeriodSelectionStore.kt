package com.spends.app.core.period

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spends.app.widget.SummaryWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.periodStore: DataStore<Preferences> by preferencesDataStore(name = "period_selection")

/**
 * Process-wide holder for the cycle/range the user picked, so the Transactions and Analytics screens stay
 * in sync. It is **persisted** (DataStore) so the selection survives a cold start AND the home-screen
 * widget can mirror it (#6): changing the period in the app updates the widget's cycle name/dates too.
 */
@Singleton
class PeriodSelectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _selection = MutableStateFlow(PeriodSelection())
    val selection: StateFlow<PeriodSelection> = _selection.asStateFlow()

    init {
        // Restore the last-used selection on construction so the UI opens where the user left off.
        scope.launch { _selection.value = readPersisted() }
    }

    fun set(selection: PeriodSelection) {
        _selection.update { selection }
        scope.launch {
            persist(selection)
            // Refresh the widget AFTER the write commits, so it re-reads the NEW selection (#6) rather than
            // racing the async write (the same ordering rule as the salary-day / widget-eye fixes).
            SummaryWidget.refresh(context)
        }
    }

    /** One-shot read of the persisted selection for the home-screen widget (RemoteViews can't collect a Flow). */
    suspend fun current(): PeriodSelection = readPersisted()

    private suspend fun readPersisted(): PeriodSelection {
        val p = context.periodStore.data.first()
        val type = p[Keys.TYPE]?.let { runCatching { PeriodType.valueOf(it) }.getOrNull() } ?: PeriodType.SALARY_CYCLE
        val range = p[Keys.RANGE]?.let { runCatching { PeriodRange.valueOf(it) }.getOrNull() } ?: PeriodRange.CURRENT
        return PeriodSelection(
            type = type,
            range = range,
            customStartMillis = p[Keys.CUSTOM_START]?.takeIf { it > 0 },
            customEndExclusiveMillis = p[Keys.CUSTOM_END]?.takeIf { it > 0 },
            cycleOffset = p[Keys.OFFSET] ?: 0,
            selectedCardId = p[Keys.SELECTED_CARD]?.takeIf { it > 0 },
        )
    }

    private suspend fun persist(s: PeriodSelection) {
        context.periodStore.edit { e ->
            e[Keys.TYPE] = s.type.name
            e[Keys.RANGE] = s.range.name
            e[Keys.CUSTOM_START] = s.customStartMillis ?: 0L
            e[Keys.CUSTOM_END] = s.customEndExclusiveMillis ?: 0L
            e[Keys.OFFSET] = s.cycleOffset
            e[Keys.SELECTED_CARD] = s.selectedCardId ?: 0L
        }
    }

    private object Keys {
        val TYPE = stringPreferencesKey("type")
        val RANGE = stringPreferencesKey("range")
        val CUSTOM_START = longPreferencesKey("custom_start")
        val CUSTOM_END = longPreferencesKey("custom_end")
        val OFFSET = intPreferencesKey("cycle_offset")
        val SELECTED_CARD = longPreferencesKey("selected_card_id")
    }
}
