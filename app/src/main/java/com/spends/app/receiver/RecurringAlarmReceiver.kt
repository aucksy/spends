package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spends.app.core.time.DateUtils
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.work.RecurringAlarmScheduler
import com.spends.app.work.RecurringNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires at the user's chosen time (default 9 AM) from the exact alarm in [RecurringAlarmScheduler] (#4):
 * materialises any due recurring rules, posts the "recurring added" heads-up when the notify toggle is on,
 * and re-arms tomorrow's alarm (exact alarms are one-shot). A plain BroadcastReceiver pulling singleton
 * deps through a Hilt EntryPoint (the same pattern as [SmsReceiver]).
 *
 * Materialisation is idempotent and serialised inside RecurringRepository, so overlap with the launch pass
 * (MainViewModel) is safe — no rule is ever double-created.
 */
class RecurringAlarmReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RecurringEntryPoint {
        fun recurringRepository(): RecurringRepository
        fun settingsRepository(): SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val entry = EntryPointAccessors.fromApplication(app, RecurringEntryPoint::class.java)
        val recurring = entry.recurringRepository()
        val settings = entry.settingsRepository()

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = settings.settings.first()
                val created = recurring.materializeDue(DateUtils.nowMillis())
                if (created > 0 && s.recurringNotifyEnabled) RecurringNotifier.notify(app, created)
                // Re-arm the next daily run at the (possibly changed) persisted time.
                RecurringAlarmScheduler.schedule(app, s.recurringNotifyMinute)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.spends.app.action.RECURRING_ALARM"
    }
}
