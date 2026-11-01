package com.spends.app.ui.home

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the salary pop-up on Home shows. */
sealed interface SalaryAsk {
    data object None : SalaryAsk

    /** Spends set the salary day by itself from the first salary; tell the owner once. */
    data class Found(val day: Int) : SalaryAsk

    /** The owner said "it hasn't come yet": ask whether this big credit is the salary. */
    data class IsThisSalary(val expenseId: Long, val amount: String, val from: String?, val date: String) : SalaryAsk
}

/**
 * The salary half of the setup journey. When the owner did not know their salary day at setup, Spends waits
 * for the salary: an SMS that says "salary" is filed under Salary by itself, and [SalaryCalendar] then sets
 * the day. Many salary SMS never say "salary", so the biggest recent credit is offered ONCE: "Is this your
 * salary?". Yes files it under Salary (and learns the sender), which sets the day the same way.
 */
@HiltViewModel
class SalaryNudgeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val captureRepository: SmsCaptureRepository,
) : ViewModel() {

    private val _ask = MutableStateFlow<SalaryAsk>(SalaryAsk.None)
    val ask: StateFlow<SalaryAsk> = _ask

    init {
        viewModelScope.launch {
            if (DemoMode.isEnabled(context)) return@launch
            val s = settingsRepository.settings.first()
            if (s.salaryDayFoundNotice in 1..31) {
                _ask.value = SalaryAsk.Found(s.salaryDayFoundNotice)
                return@launch
            }
            if (!s.salaryDayPending) return@launch
            val salaryId = categoryRepository.getByName("Salary")?.id ?: return@launch
            val now = DateUtils.nowMillis()
            val asked = prefs().getStringSet(ASKED, emptySet()).orEmpty()
            val candidate = expenseRepository.expensesBetweenOnce(now - LOOK_BACK_MILLIS, now + 1)
                .filter { it.kind == TxnKind.INCOME && it.amountMinor >= MIN_SALARY_MINOR && it.id.toString() !in asked }
                .filter { e -> expenseRepository.getById(e.id)?.allocations?.none { it.allocation.categoryId == salaryId } == true }
                .maxByOrNull { it.amountMinor } ?: return@launch
            _ask.value = SalaryAsk.IsThisSalary(
                expenseId = candidate.id,
                amount = Money.format(candidate.amountMinor, alwaysTwoDecimals = false),
                from = candidate.merchantRaw?.takeIf { it.isNotBlank() },
                date = DateUtils.formatDay(candidate.occurredAt),
            )
        }
    }

    fun confirmFound() {
        _ask.value = SalaryAsk.None
        viewModelScope.launch { settingsRepository.setSalaryDayFoundNotice(0) }
    }

    /** "Yes, it's my salary" — file it under Salary; SalaryCalendar then sets the day and says so. */
    fun yesSalary(expenseId: Long) {
        _ask.value = SalaryAsk.None
        viewModelScope.launch {
            val salaryId = categoryRepository.getByName("Salary")?.id ?: return@launch
            expenseRepository.reassignCategory(expenseId, salaryId)
            runCatching { captureRepository.learnFromTransaction(expenseId, salaryId) }
        }
    }

    /** "No" — never offer this credit again; a later, bigger one may still be offered. */
    fun notSalary(expenseId: Long) {
        _ask.value = SalaryAsk.None
        val asked = prefs().getStringSet(ASKED, emptySet()).orEmpty() + expenseId.toString()
        prefs().edit().putStringSet(ASKED, asked).apply()
    }

    fun later() {
        _ask.value = SalaryAsk.None
    }

    private fun prefs() = context.getSharedPreferences("salary_nudge", Context.MODE_PRIVATE)

    private companion object {
        const val ASKED = "asked_expense_ids"
        const val LOOK_BACK_MILLIS = 40L * 24 * 60 * 60 * 1000
        /** ₹5,000 — smaller credits are almost never a monthly salary. */
        const val MIN_SALARY_MINOR = 5_000_00L
    }
}

@Composable
fun SalaryNudgeDialog(viewModel: SalaryNudgeViewModel = hiltViewModel()) {
    val ask by viewModel.ask.collectAsState()
    when (val a = ask) {
        SalaryAsk.None -> Unit
        is SalaryAsk.Found -> AlertDialog(
            onDismissRequest = viewModel::confirmFound,
            title = { Text("Spends found your salary") },
            text = {
                Text(
                    "Your salary came on the ${ordinal(a.day)}. Each month now starts on the day your salary comes, " +
                        "so you see how much of each salary you have spent.\n\n" +
                        "If a salary comes a few days early or late, Spends moves that month's start to match. " +
                        "You can change this in Settings → Money & Cycles.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmFound) { Text("OK") } },
        )
        is SalaryAsk.IsThisSalary -> AlertDialog(
            onDismissRequest = viewModel::later,
            title = { Text("Is this your salary?") },
            text = {
                Text(
                    "${a.amount}${a.from?.let { " from $it" } ?: ""} came on ${a.date}.\n\n" +
                        "If it is your salary, Spends starts each month on the day your salary comes.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.yesSalary(a.expenseId) }) { Text("Yes, it's my salary") } },
            dismissButton = { TextButton(onClick = { viewModel.notSalary(a.expenseId) }) { Text("No") } },
        )
    }
}

private fun ordinal(day: Int): String {
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
