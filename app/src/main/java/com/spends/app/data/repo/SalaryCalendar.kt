package com.spends.app.data.repo

import com.spends.app.core.time.DateUtils
import com.spends.app.core.time.SalaryDates
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the salary cycles on the days salary REALLY came. Watches income filed under Salary (an SMS that
 * says "salary" is filed there by itself) and:
 *  - saves each month's real start ([SalaryDates.derive]) so every screen and the widget follow it;
 *  - when the owner said at setup that their salary had not come yet, takes the first salary's day as
 *    the usual day and leaves a one-time notice for Home.
 * Started once from the Application; runs for the life of the process.
 */
@Singleton
class SalaryCalendar @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val settingsRepository: SettingsRepository,
) {

    private data class Inputs(val usualDay: Int, val follow: Boolean, val pending: Boolean, val saved: String)

    fun start(scope: CoroutineScope) {
        scope.launch {
            val inputs = settingsRepository.settings
                .map {
                    Inputs(
                        it.salaryCycleStartDay,
                        it.followSalaryDate,
                        it.salaryDayPending,
                        SalaryDates.encode(SalaryDates.Calendar(it.salaryCycleStartDay, it.salaryStarts)),
                    )
                }
                .distinctUntilChanged()
            val credits = expenseDao.observeSalaryCredits()
                .map { list -> list.map { DateUtils.toLocalDate(it.occurredAt) to it.amountMinor } }
                .distinctUntilChanged()
            combine(inputs, credits) { i, c -> i to c }.collect { (i, c) ->
                runCatching { update(i, c) }
            }
        }
    }

    private suspend fun update(i: Inputs, credits: List<Pair<java.time.LocalDate, Long>>) {
        if (i.pending) {
            val day = SalaryDates.latestSalaryDay(credits) ?: return
            settingsRepository.setSalaryCycleStartDay(day.dayOfMonth)
            settingsRepository.setSalaryDayPending(false)
            settingsRepository.setSalaryDayFoundNotice(day.dayOfMonth)
            return // the day change re-runs this with the new usual day
        }
        // Following off: the settings flow already ignores saved starts, and they come back if it is turned on.
        if (!i.follow) return
        val want = SalaryDates.Calendar(i.usualDay, SalaryDates.derive(credits, i.usualDay))
        if (SalaryDates.encode(want) != i.saved) settingsRepository.setSalaryStarts(want)
    }
}
