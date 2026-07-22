package com.spends.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.spends.app.R
import com.spends.app.core.MainActivity
import com.spends.app.core.QuickAddActivity
import com.spends.app.core.money.Money
import com.spends.app.core.period.CardCycleInfo
import com.spends.app.core.period.CompositeCycleResolver
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
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
        fun periodSelectionStore(): com.spends.app.core.period.PeriodSelectionStore
        fun paymentMethodRepository(): PaymentMethodRepository
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = entryPoint(context).widgetMaskStore()
        appWidgetIds.forEach { store.clear(it); cancelAutoHide(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // dispatches APPWIDGET_UPDATE -> onUpdate
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_TOGGLE_MASK -> if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // The haptic fires from WidgetEyeToggleActivity (a foreground context) — this broadcast runs
                // in the background, where many OEMs suppress vibration (#1). Here we just toggle + render.
                val store = entryPoint(context).widgetMaskStore()
                store.toggle(id)
                // Revealed → auto-hide after 5s (#5); hidden again manually → cancel the pending auto-hide.
                if (!store.isMasked(id)) scheduleAutoHide(context, id) else cancelAutoHide(context, id)
                renderAsync(context, AppWidgetManager.getInstance(context), intArrayOf(id))
            }
            ACTION_AUTO_HIDE -> if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                entryPoint(context).widgetMaskStore().mask(id)
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
                // Mirror whatever the user last selected in the app (#6) — read one-shot from the persisted
                // store. ALL falls back to PeriodResolver's 5-year floor since the widget doesn't fetch the
                // earliest day.
                val selection = ep.periodSelectionStore().current()
                val today = LocalDate.now(DateUtils.ZONE)

                var income = 0L
                var expense = 0L
                var cycleName = ""
                var cycleLabel = ""
                val singleCard = settings.smartCycleEnabled && selection.type == PeriodType.SMART_CYCLE &&
                    selection.selectedCardId != null
                var handledAsCard = false
                if (singleCard) {
                    // Single-Card mode: that one card's own billing cycle (composite machinery).
                    val cards = ep.paymentMethodRepository().observeConfirmed().first()
                    val infos = cards.map { CardCycleInfo(it.id, it.label, it.colorHex, it.last4, it.billingDay) }
                    val card = infos.firstOrNull { it.id == selection.selectedCardId }
                    if (card != null) {
                        val composite = CompositeCycleResolver.resolveSingleCard(card, settings.salaryCycleStartDay, today, selection.cycleOffset)
                        val items = ep.expenseRepository()
                            .expensesBetweenOnce(composite.boundingStartMillis, composite.boundingEndExclusiveMillis)
                            .filter { composite.contains(it.occurredAt, it.paymentMethodId) }
                        income = items.filter { it.kind == TxnKind.INCOME }.sumOf { it.amountMinor }
                        expense = items.filter { it.kind == TxnKind.EXPENSE }.sumOf { it.amountMinor }
                        // Just the cycle name (a single card's label already carries its dates) (#1).
                        cycleName = composite.label
                        cycleLabel = ""
                        handledAsCard = true
                    }
                }
                if (!handledAsCard) {
                    // A stale SMART_CYCLE selection while the feature is off falls back to the salary cycle.
                    // With the feature ON, Smart Cycle is one contiguous window anchored on the reset day
                    // (default = salary day) — same window the app's timeline shows.
                    val effType = if (!settings.smartCycleEnabled && selection.type == PeriodType.SMART_CYCLE) {
                        PeriodType.SALARY_CYCLE
                    } else {
                        selection.type
                    }
                    val resolved = PeriodResolver.resolve(
                        type = effType,
                        range = selection.range,
                        salaryDay = settings.salaryCycleStartDay,
                        smartDay = settings.effectiveSmartResetDay,
                        today = today,
                        earliestDataDay = null,
                        customStartMillis = selection.customStartMillis,
                        customEndExclusiveMillis = selection.customEndExclusiveMillis,
                        cycleOffset = selection.cycleOffset,
                    )
                    val sums = ep.expenseRepository().kindSumsOnce(resolved.startMillis, resolved.endExclusiveMillis)
                    income = sums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0L
                    expense = sums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0L
                    // Cycle NAME + dates (#11), reflecting the user's actual selection (#6). A vanished
                    // single card falls through to here — its describe() would say "Single Card" over
                    // whole-cycle numbers, so show the window's dates instead.
                    cycleName = if (singleCard) resolved.label else selection.describe()
                    cycleLabel = if (singleCard) "" else resolved.label
                }
                val balance = income - expense
                val store = ep.widgetMaskStore()
                val eyeHidden = settings.widgetEyeHidden
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        buildViews(context, id, cycleName, cycleLabel, income, expense, balance, store.isMasked(id), eyeHidden),
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
        cycleName: String,
        cycleLabel: String,
        income: Long,
        expense: Long,
        balance: Long,
        masked: Boolean,
        eyeHidden: Boolean,
    ): RemoteViews {
        fun money(v: Long) = if (masked) MASK else Money.formatRupees(v, alwaysTwoDecimals = false)
        // Show "Name · dates" when there's a concrete date range; just the name when there isn't (e.g. the
        // Smart Cycle composite, whose instruments each have their own window — #1/#5).
        val cycleHeader = if (cycleLabel.isBlank()) cycleName else "$cycleName · $cycleLabel"
        return RemoteViews(context.packageName, R.layout.widget_summary).apply {
            setTextViewText(R.id.widget_summary_cycle, cycleHeader)
            setTextViewText(R.id.widget_summary_balance, money(balance))
            setTextViewText(R.id.widget_summary_expense, money(expense))
            setTextViewText(R.id.widget_summary_income, money(income))
            // Invisible-but-tappable eye (#3): when hidden, blank BOTH the circle background and the icon so
            // the button reads as empty space — yet it stays clickable (the tap target/PendingIntent remain),
            // so the owner can still reveal but a bystander can't tell there's a control there.
            if (eyeHidden) {
                setInt(R.id.widget_summary_eye, "setBackgroundResource", android.R.color.transparent)
                setImageViewResource(R.id.widget_summary_eye, android.R.color.transparent)
            } else {
                setInt(R.id.widget_summary_eye, "setBackgroundResource", R.drawable.widget_circle_button)
                setImageViewResource(R.id.widget_summary_eye, if (masked) R.drawable.ic_widget_eye else R.drawable.ic_widget_eye_off)
            }
            setContentDescription(R.id.widget_summary_eye, if (masked) "Show amounts" else "Hide amounts")
            setOnClickPendingIntent(R.id.widget_summary_root, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_summary_eye, toggleIntent(context, id))
            // The "+" opens the standalone quick-add overlay directly (no app/list behind it).
            setOnClickPendingIntent(R.id.widget_summary_add, openQuickAddIntent(context))
        }
    }

    private fun autoHideIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, SummaryWidget::class.java).apply {
            action = ACTION_AUTO_HIDE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        return PendingIntent.getBroadcast(context, AUTO_HIDE_REQ_BASE + id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Re-mask this widget ~5s after it was revealed (#5). Uses ELAPSED_REALTIME (a relative timeout that's
     *  immune to wall-clock jumps), inexact — the screen is on (the user just tapped) so it fires promptly;
     *  an exact alarm would need a runtime permission on Android 12+. */
    private fun scheduleAutoHide(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            am.set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + AUTO_HIDE_MS, autoHideIntent(context, id))
        }
    }

    private fun cancelAutoHide(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(autoHideIntent(context, id)) }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openQuickAddIntent(context: Context): PendingIntent {
        val intent = Intent(context, QuickAddActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        // Distinct requestCode from openAppIntent(0) so the two PendingIntents don't collide.
        return PendingIntent.getActivity(context, 4202, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun toggleIntent(context: Context, id: Int): PendingIntent {
        // The eye launches an invisible FOREGROUND trampoline so it can actually vibrate (#1 — a background
        // broadcast can't on many OEMs); the trampoline fires the haptic, then sends the toggle broadcast.
        val intent = Intent(context, WidgetEyeToggleActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(WidgetEyeToggleActivity.EXTRA_WIDGET_ID, id)
        }
        // Distinct requestCode per id so each widget's eye PendingIntent stays separate.
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val ACTION_TOGGLE_MASK = "com.spends.app.widget.TOGGLE_MASK"
        private const val ACTION_AUTO_HIDE = "com.spends.app.widget.AUTO_HIDE"
        private const val MASK = "••••"
        private const val AUTO_HIDE_MS = 5_000L
        private const val AUTO_HIDE_REQ_BASE = 90_000 // distinct PendingIntent request-code space per widget id

        /** Send the eye's mask-toggle broadcast — called by [WidgetEyeToggleActivity] after it fires the
         *  haptic from the foreground (the broadcast itself can't vibrate from the background, #1). The
         *  ACTION + extra stay private to this class. */
        fun sendToggle(context: Context, id: Int) {
            val intent = Intent(context, SummaryWidget::class.java).apply {
                action = ACTION_TOGGLE_MASK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            context.sendBroadcast(intent)
        }

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
