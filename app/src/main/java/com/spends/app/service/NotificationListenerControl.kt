package com.spends.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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

    /**
     * Whether the listener is bound RIGHT NOW, set by the service's connect/disconnect callbacks. Lives
     * here rather than on the (temporary) debug log so that deleting the diagnostic can't take the
     * permanent rebind logic down with it.
     */
    @Volatile
    var connected: Boolean = false
        private set

    fun setConnected(value: Boolean) {
        connected = value
    }

    /** Has the user granted Android's "Notification access" to Spends? (Grant, not binding.) */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Ask the system to (re)bind our listener. Returns whether the request was actually ISSUED (i.e.
     * the grant exists) — not whether a rebind followed.
     *
     * Honest limitation: `requestRebind` is documented for the post-`requestUnbind` case, and on AOSP
     * it routes through `ManagedServices.setComponentState`, which does nothing for a component that
     * was never snoozed. So for a binding lost to an app update or an OEM kill this may well be a
     * no-op. It is free and occasionally sufficient, so we always try — but the reliable remedy is the
     * user toggling notification access off and on, which is why the debug screen offers that too.
     */
    fun requestRebind(context: Context): Boolean {
        if (!hasAccess(context)) return false
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context.applicationContext, CaptureNotificationListenerService::class.java),
            )
        }
        return true
    }

    /**
     * Rebind only when we are not already connected — an unnecessary rebind cycles the service (and
     * re-runs its shade sweep), so the healthy case is skipped.
     */
    fun ensureBound(context: Context) {
        if (connected) return
        requestRebind(context)
    }

    /**
     * Deep-link to our row in Android's notification-access settings (Android 11+), else the general
     * list. Toggling access off and on there is the reliable way to force a rebind when [requestRebind]
     * turns out to be a no-op.
     */
    fun openAccessSettings(context: Context) {
        val component = ComponentName(context, CaptureNotificationListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.startActivity(detail) }.isSuccess
        } else {
            false
        }
        if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    /**
     * How long to let the SYSTEM bind us on its own before we ask. On a normal launch the binding
     * arrives within a moment of process start; only a genuinely broken binding is still absent after
     * this, so the wait keeps the healthy path free of a pointless unbind/rebind.
     */
    const val BIND_GRACE_MILLIS: Long = 5_000
}
