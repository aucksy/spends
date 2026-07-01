package com.spends.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.SmsCaptureMode
import com.spends.app.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Immutable snapshot of all user preferences. */
data class SettingsState(
    val onboardingComplete: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Dormant since v0.12.0 (Material You removed — we always use the design-system green). Kept so old
    // backups still deserialize; the theme no longer reads it.
    val dynamicColor: Boolean = false,
    // Auto theme window in minutes-of-day: dark from [autoDarkStartMinute, autoDarkEndMinute). Default
    // 20:00 → 06:00 (wraps past midnight). Only used when themeMode == AUTO.
    val autoDarkStartMinute: Int = 20 * 60,
    val autoDarkEndMinute: Int = 6 * 60,
    val salaryCycleStartDay: Int = 1,
    val defaultLanding: DefaultLanding = DefaultLanding.TRANSACTIONS,
    val carryForwardEnabled: Boolean = false,
    // Carry-forward only counts from this anchor date onward (0 = no anchor → count everything), with an
    // optional opening balance as of that date. Fixes a hugely-negative carry-forward from incomplete history.
    val carryForwardAnchorEpochDay: Long = 0,
    val carryForwardOpeningMinor: Long = 0,
    val trashRetentionDays: Int = 30,
    val autoBackupEnabled: Boolean = false,
    // Minutes-of-day the daily Drive backup aims for (default 02:00 — a quiet overnight hour). WorkManager
    // runs it about once a day near this time, deferring while the device is offline (a backup needs a
    // connection) or dozing, then catching up. User-chosen via Settings; travels in the backup snapshot.
    val autoBackupMinuteOfDay: Int = 2 * 60,
    val smsCaptureEnabled: Boolean = false,
    val smsCaptureMode: SmsCaptureMode = SmsCaptureMode.AUTO_ADD,
    val hideCapturedInLists: Boolean = false,
    // Hide the summary widget's eye button so it's invisible-but-tappable (#3): others can't tell there's a
    // reveal control. Device-local widget pref — not part of the backup snapshot.
    val widgetEyeHidden: Boolean = false,
    // Smart Cycle / Cards feature master switch (PRD §4.7/§4.8). OFF by default → the app behaves exactly
    // as before (no cards, no "Paid with", no Cards tab). ON reveals the cards machinery. Travels in backup.
    val smartCycleEnabled: Boolean = false,
    // Whether the daily recurring-materialisation worker posts a "recurring added" notification, and the
    // minute-of-day it runs/notifies (#15). Device-local (like the widget-eye pref) — not in the backup
    // snapshot, so it never needs a snapshot-schema bump. ON at 09:00 by default = the prior behaviour.
    val recurringNotifyEnabled: Boolean = true,
    val recurringNotifyMinute: Int = 9 * 60,
    // The instrument a NEW expense pre-selects in "Paid with" (#2). null = the generic Bank. Device-local
    // (a payment-method id is meaningless across devices), so it's NOT in the backup snapshot. 0 in the
    // store means "unset" → null here.
    val defaultPaymentMethodId: Long? = null,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.dataStore

    val settings: Flow<SettingsState> = store.data.map { prefs ->
        SettingsState(
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            themeMode = prefs[Keys.THEME_MODE]?.toThemeMode() ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            autoDarkStartMinute = prefs[Keys.AUTO_DARK_START] ?: (20 * 60),
            autoDarkEndMinute = prefs[Keys.AUTO_DARK_END] ?: (6 * 60),
            salaryCycleStartDay = prefs[Keys.SALARY_DAY] ?: 1,
            defaultLanding = prefs[Keys.DEFAULT_LANDING]?.toLanding() ?: DefaultLanding.TRANSACTIONS,
            carryForwardEnabled = prefs[Keys.CARRY_FORWARD] ?: false,
            carryForwardAnchorEpochDay = prefs[Keys.CARRY_FORWARD_ANCHOR] ?: 0,
            carryForwardOpeningMinor = prefs[Keys.CARRY_FORWARD_OPENING] ?: 0,
            trashRetentionDays = prefs[Keys.TRASH_RETENTION_DAYS] ?: 30,
            autoBackupEnabled = prefs[Keys.AUTO_BACKUP] ?: false,
            autoBackupMinuteOfDay = prefs[Keys.AUTO_BACKUP_MINUTE] ?: (2 * 60),
            smsCaptureEnabled = prefs[Keys.SMS_CAPTURE] ?: false,
            smsCaptureMode = prefs[Keys.SMS_CAPTURE_MODE]?.toCaptureMode() ?: SmsCaptureMode.AUTO_ADD,
            hideCapturedInLists = prefs[Keys.HIDE_CAPTURED] ?: false,
            widgetEyeHidden = prefs[Keys.WIDGET_EYE_HIDDEN] ?: false,
            smartCycleEnabled = prefs[Keys.SMART_CYCLE_ENABLED] ?: false,
            recurringNotifyEnabled = prefs[Keys.RECURRING_NOTIFY] ?: true,
            recurringNotifyMinute = prefs[Keys.RECURRING_NOTIFY_MINUTE] ?: (9 * 60),
            defaultPaymentMethodId = prefs[Keys.DEFAULT_PAYMENT_METHOD]?.takeIf { it > 0 },
        )
    }

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.ONBOARDING_COMPLETE] = value }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setAutoDarkWindow(startMinute: Int, endMinute: Int) = edit {
        it[Keys.AUTO_DARK_START] = startMinute.coerceIn(0, 1439)
        it[Keys.AUTO_DARK_END] = endMinute.coerceIn(0, 1439)
    }
    suspend fun setSalaryCycleStartDay(day: Int) = edit { it[Keys.SALARY_DAY] = day.coerceIn(1, 31) }
    suspend fun setDefaultLanding(landing: DefaultLanding) = edit { it[Keys.DEFAULT_LANDING] = landing.name }
    suspend fun setCarryForwardEnabled(value: Boolean) = edit { it[Keys.CARRY_FORWARD] = value }
    suspend fun setCarryForwardAnchor(epochDay: Long) = edit { it[Keys.CARRY_FORWARD_ANCHOR] = epochDay }
    suspend fun setCarryForwardOpening(minor: Long) = edit { it[Keys.CARRY_FORWARD_OPENING] = minor }
    suspend fun setTrashRetentionDays(days: Int) = edit { it[Keys.TRASH_RETENTION_DAYS] = days.coerceIn(1, 365) }
    suspend fun setAutoBackupEnabled(value: Boolean) = edit { it[Keys.AUTO_BACKUP] = value }
    suspend fun setAutoBackupTime(minuteOfDay: Int) = edit { it[Keys.AUTO_BACKUP_MINUTE] = minuteOfDay.coerceIn(0, 1439) }
    suspend fun setSmsCaptureEnabled(value: Boolean) = edit { it[Keys.SMS_CAPTURE] = value }
    suspend fun setSmsCaptureMode(mode: SmsCaptureMode) = edit { it[Keys.SMS_CAPTURE_MODE] = mode.name }
    suspend fun setHideCapturedInLists(value: Boolean) = edit { it[Keys.HIDE_CAPTURED] = value }
    suspend fun setWidgetEyeHidden(value: Boolean) = edit { it[Keys.WIDGET_EYE_HIDDEN] = value }
    suspend fun setSmartCycleEnabled(value: Boolean) = edit { it[Keys.SMART_CYCLE_ENABLED] = value }
    suspend fun setRecurringNotifyEnabled(value: Boolean) = edit { it[Keys.RECURRING_NOTIFY] = value }
    suspend fun setRecurringNotifyTime(minuteOfDay: Int) = edit { it[Keys.RECURRING_NOTIFY_MINUTE] = minuteOfDay.coerceIn(0, 1439) }
    /** Set the default "Paid with" instrument for new expenses (#2); null = generic Bank (stored as 0). */
    suspend fun setDefaultPaymentMethodId(id: Long?) = edit { it[Keys.DEFAULT_PAYMENT_METHOD] = id ?: 0L }

    /** Overwrite every preference from a restored snapshot. */
    suspend fun restore(state: SettingsState) {
        store.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETE] = state.onboardingComplete
            prefs[Keys.THEME_MODE] = state.themeMode.name
            prefs[Keys.DYNAMIC_COLOR] = state.dynamicColor
            prefs[Keys.AUTO_DARK_START] = state.autoDarkStartMinute
            prefs[Keys.AUTO_DARK_END] = state.autoDarkEndMinute
            prefs[Keys.SALARY_DAY] = state.salaryCycleStartDay
            prefs[Keys.DEFAULT_LANDING] = state.defaultLanding.name
            prefs[Keys.CARRY_FORWARD] = state.carryForwardEnabled
            prefs[Keys.CARRY_FORWARD_ANCHOR] = state.carryForwardAnchorEpochDay
            prefs[Keys.CARRY_FORWARD_OPENING] = state.carryForwardOpeningMinor
            prefs[Keys.TRASH_RETENTION_DAYS] = state.trashRetentionDays
            prefs[Keys.AUTO_BACKUP] = state.autoBackupEnabled
            prefs[Keys.AUTO_BACKUP_MINUTE] = state.autoBackupMinuteOfDay
            prefs[Keys.HIDE_CAPTURED] = state.hideCapturedInLists
            prefs[Keys.SMART_CYCLE_ENABLED] = state.smartCycleEnabled
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private fun String.toThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(this) }.getOrDefault(ThemeMode.SYSTEM)

    private fun String.toLanding(): DefaultLanding =
        runCatching { DefaultLanding.valueOf(this) }.getOrDefault(DefaultLanding.TRANSACTIONS)

    private fun String.toCaptureMode(): SmsCaptureMode =
        runCatching { SmsCaptureMode.valueOf(this) }.getOrDefault(SmsCaptureMode.AUTO_ADD)

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AUTO_DARK_START = intPreferencesKey("auto_dark_start_minute")
        val AUTO_DARK_END = intPreferencesKey("auto_dark_end_minute")
        val SALARY_DAY = intPreferencesKey("salary_cycle_start_day")
        val DEFAULT_LANDING = stringPreferencesKey("default_landing")
        val CARRY_FORWARD = booleanPreferencesKey("carry_forward_enabled")
        val CARRY_FORWARD_ANCHOR = longPreferencesKey("carry_forward_anchor_epoch_day")
        val CARRY_FORWARD_OPENING = longPreferencesKey("carry_forward_opening_minor")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_MINUTE = intPreferencesKey("auto_backup_minute_of_day")
        val SMS_CAPTURE = booleanPreferencesKey("sms_capture_enabled")
        val SMS_CAPTURE_MODE = stringPreferencesKey("sms_capture_mode")
        val HIDE_CAPTURED = booleanPreferencesKey("hide_captured_in_lists")
        val WIDGET_EYE_HIDDEN = booleanPreferencesKey("widget_eye_hidden")
        val SMART_CYCLE_ENABLED = booleanPreferencesKey("smart_cycle_enabled")
        val RECURRING_NOTIFY = booleanPreferencesKey("recurring_notify_enabled")
        val RECURRING_NOTIFY_MINUTE = intPreferencesKey("recurring_notify_minute")
        val DEFAULT_PAYMENT_METHOD = longPreferencesKey("default_payment_method_id")
    }
}
