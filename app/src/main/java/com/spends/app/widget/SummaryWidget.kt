package com.spends.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.spends.app.R
import com.spends.app.core.MainActivity
import com.spends.app.core.money.Money
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.TxnKind
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Home-screen summary widget (#2): Income / Expense / Balance for the CURRENT salary cycle, with a
 * per-widget eye to hide/show the amounts (masked by default — see [WidgetMaskStore]). RemoteViews can't
 * run Compose or collect Flows, so totals are read one-shot off the main thread via a Hilt EntryPoint
 * (the [CaptureActionReceiver]-style pattern), and the period is resolved deterministically (current
 * salary cycle) rather than from the in-memory selection store, which resets on cold start.
 */
class SummaryWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun expenseRepository(): ExpenseRepository
        fun settingsRepository(): SettingsRepository
        fun widgetMaskStore(): WidgetMaskStore
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = entryPoint(context).widgetMaskStore()
        appWidgetIds.forEach { store.clear(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // dispatches APPWIDGET_UPDATE -> onUpdate
        if (intent.action == ACTION_TOGGLE_MASK) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                entryPoint(context).widgetMaskStore().toggle(id)
                renderAsync(context, AppWidgetManager.getInstance(context), intArrayOf(id))
            }
        }
    }

    private fun renderAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val ep = entryPoint(context)
                val settings = ep.settingsRepository().settings.first()
                val resolved = PeriodResolver.resolve(
                    type = PeriodType.SALARY_CYCLE,
                    range = PeriodRange.CURRENT,
                    salaryDay = settings.salaryCycleStartDay,
                    smartDay = settings.salaryCycleStartDay, // unused for CURRENT/SALARY_CYCLE
                    today = LocalDate.now(DateUtils.ZONE),
                    earliestDataDay = null,
                    customStartMillis = null,
                    customEndExclusiveMillis = null,
                )
                val sums = ep.expenseRepository().kindSumsOnce(resolved.startMillis, resolved.endExclusiveMillis)
                val income = sums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0L
                val expense = sums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0L
                val balance = income - expense
                val store = ep.widgetMaskStore()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        buildViews(context, id, resolved.label, income, expense, balance, store.isMasked(id)),
                    )
                }
            } catch (e: Exception) {
                // Never crash the launcher's broadcast — a stale widget is fine until the next refresh.
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(
        context: Context,
        id: Int,
        cycleLabel: String,
        income: Long,
        expense: Long,
        balance: Long,
        masked: Boolean,
    ): RemoteViews {
        fun money(v: Long) = if (masked) MASK else Money.formatRupees(v, alwaysTwoDecimals = false)
        return RemoteViews(context.packageName, R.layout.widget_summary).apply {
            setTextViewText(R.id.widget_summary_cycle, "BALANCE · $cycleLabel")
            setTextViewText(R.id.widget_summary_balance, money(balance))
            setTextViewText(R.id.widget_summary_expense, money(expense))
            setTextViewText(R.id.widget_summary_income, money(income))
            setImageViewResource(R.id.widget_summary_eye, if (masked) R.drawable.ic_widget_eye else R.drawable.ic_widget_eye_off)
            setContentDescription(R.id.widget_summary_eye, if (masked) "Show amounts" else "Hide amounts")
            setOnClickPendingIntent(R.id.widget_summary_root, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_summary_eye, toggleIntent(context, id))
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun toggleIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, SummaryWidget::class.java).apply {
            action = ACTION_TOGGLE_MASK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        // Distinct requestCode per id so each widget's eye PendingIntent stays separate.
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val ACTION_TOGGLE_MASK = "com.spends.app.widget.TOGGLE_MASK"
        private const val MASK = "••••"

        /** Refresh every summary-widget instance — call when app data may have changed (e.g. app resume). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, SummaryWidget::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, SummaryWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}

private fun entryPoint(context: Context): SummaryWidget.WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, SummaryWidget.WidgetEntryPoint::class.java)
