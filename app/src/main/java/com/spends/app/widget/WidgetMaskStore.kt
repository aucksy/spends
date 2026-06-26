package com.spends.app.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-widget privacy state for the summary widget (#2): which instances currently SHOW their amounts.
 * A widget is **masked (hidden) by default** — privacy-first, matching the in-app eye — and tapping its
 * eye flips it. Plain SharedPreferences so RemoteViews can read it synchronously off the main thread.
 */
@Singleton
class WidgetMaskStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("spends_widget", Context.MODE_PRIVATE)

    /** True (hidden) unless this widget has been explicitly revealed. */
    fun isMasked(appWidgetId: Int): Boolean = appWidgetId.toString() !in shown()

    /** Flip this widget between masked and shown. */
    fun toggle(appWidgetId: Int) {
        val key = appWidgetId.toString()
        val set = shown().toMutableSet()
        if (key in set) set.remove(key) else set.add(key)
        prefs.edit().putStringSet(KEY_SHOWN, set).apply()
    }

    /** Drop a removed widget's state so a recycled id doesn't inherit it. */
    fun clear(appWidgetId: Int) {
        val key = appWidgetId.toString()
        if (key in shown()) prefs.edit().putStringSet(KEY_SHOWN, shown() - key).apply()
    }

    private fun shown(): Set<String> = prefs.getStringSet(KEY_SHOWN, emptySet()) ?: emptySet()

    private companion object {
        const val KEY_SHOWN = "shown_ids"
    }
}
