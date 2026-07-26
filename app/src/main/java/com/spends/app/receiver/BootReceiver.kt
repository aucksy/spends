package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.service.NotificationListenerControl
import com.spends.app.work.RecurringAlarmScheduler
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
 * Re-arms the daily recurring exact alarm after a reboot (#4). Exact alarms don't survive a device restart,
 * so without this the 9 AM reminder would stop firing until the app is next opened. The daily Drive backup
 * rides WorkManager (which does persist across reboots), so only the alarm needs re-arming here.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        val app = context.applicationContext
        val settings = EntryPointAccessors.fromApplication(app, BootEntryPoint::class.java).settingsRepository()

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = settings.settings.first()
                RecurringAlarmScheduler.schedule(app, s.recurringNotifyMinute)
                // Re-assert the notification-listener binding too: a reboot is one of the ways it gets
                // lost while the grant (and the Settings toggle) keep reading "on". Nothing is bound yet
                // this early, so ask unconditionally rather than checking a connected flag.
                if (s.notificationCaptureEnabled) NotificationListenerControl.requestRebind(app)
            } finally {
                pending.finish()
            }
        }
    }
}
