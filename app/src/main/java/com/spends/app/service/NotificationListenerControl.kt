package com.spends.app.service

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps [CaptureNotificationListenerService] actually BOUND, not merely permitted.
 *
 * Android's notification-access grant and the live binding are two different things: the grant
 * survives forever, but the binding is lost on an app update and can be dropped by an OEM battery
 * killer or memory pressure — and it does not reliably come back on its own. When that happens the
 * Settings toggle still reads ON and Android still lists Spends as granted, while nothing is captured
 * at all. Nothing surfaces the difference, which is exactly how notification capture can look
 * "enabled but dead" indefinitely.
 *
 * Before this existed, the ONLY proactive rebind was the Settings switch (and
 * [CaptureNotificationListenerService.onListenerDisconnected], which can only run while our process is
 * still alive). Now app launch and boot both re-assert the binding.
 */
object NotificationListenerControl {

    /** Has the user granted Android's "Notification access" to Spends? (Grant, not binding.) */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Ask the system to (re)bind our listener. No-op without the grant; safe to call repeatedly. */
    fun requestRebind(context: Context) {
        if (!hasAccess(context)) return
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context.applicationContext, CaptureNotificationListenerService::class.java),
            )
        }
    }

    /**
     * Rebind only when we are not already connected — an unnecessary rebind cycles the service (and
     * re-runs its shade sweep), so callers pass the live connection state and we skip the healthy case.
     */
    fun ensureBound(context: Context, alreadyConnected: Boolean) {
        if (alreadyConnected) return
        requestRebind(context)
    }

    /**
     * How long to let the SYSTEM bind us on its own before we ask. On a normal launch the binding
     * arrives within a moment of process start; only a genuinely broken binding is still absent after
     * this, so the wait keeps the healthy path free of a pointless unbind/rebind.
     */
    const val BIND_GRACE_MILLIS: Long = 5_000
}
