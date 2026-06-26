package com.spends.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.spends.app.R
import com.spends.app.core.QuickAddActivity

/**
 * A small home-screen widget (#14): a single tap opens the quick-add keypad. It shows no figures
 * (balances stay private) — just an "add" affordance. Launches the standalone transparent
 * [QuickAddActivity] so only the keypad sheet appears (never the full app / list behind it), and a future
 * app-lock can't block it (#1).
 */
class QuickAddWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildRemoteViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    private fun buildRemoteViews(context: Context): RemoteViews {
        val intent = Intent(context, QuickAddActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_QUICK_ADD,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        }
    }

    private companion object {
        const val REQUEST_OPEN_QUICK_ADD = 4201
    }
}
