package com.spends.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.spends.app.R
import com.spends.app.core.MainActivity

/**
 * A small home-screen widget (#14): a single tap opens the app's quick-add sheet. It shows no figures
 * (balances stay private) — just an "add" affordance. Launches [MainActivity] with the
 * [MainActivity.EXTRA_OPEN_QUICK_ADD] extra, which the nav host turns into the quick-add bottom sheet.
 */
class QuickAddWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildRemoteViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    private fun buildRemoteViews(context: Context): RemoteViews {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(MainActivity.EXTRA_OPEN_QUICK_ADD, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
