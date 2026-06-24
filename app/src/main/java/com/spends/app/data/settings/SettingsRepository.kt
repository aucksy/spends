package com.spends.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spends.app.domain.model.DefaultLanding
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
    val dynamicColor: Boolean = true,
    val salaryCycleStartDay: Int = 1,
    val defaultLanding: DefaultLanding = DefaultLanding.TRANSACTIONS,
    val carryForwardEnabled: Boolean = false,
    val trashRetentionDays: Int = 30,
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
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            salaryCycleStartDay = prefs[Keys.SALARY_DAY] ?: 1,
            defaultLanding = prefs[Keys.DEFAULT_LANDING]?.toLanding() ?: DefaultLanding.TRANSACTIONS,
            carryForwardEnabled = prefs[Keys.CARRY_FORWARD] ?: false,
            trashRetentionDays = prefs[Keys.TRASH_RETENTION_DAYS] ?: 30,
        )
    }

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.ONBOARDING_COMPLETE] = value }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setSalaryCycleStartDay(day: Int) = edit { it[Keys.SALARY_DAY] = day.coerceIn(1, 31) }
    suspend fun setDefaultLanding(landing: DefaultLanding) = edit { it[Keys.DEFAULT_LANDING] = landing.name }
    suspend fun setCarryForwardEnabled(value: Boolean) = edit { it[Keys.CARRY_FORWARD] = value }
    suspend fun setTrashRetentionDays(days: Int) = edit { it[Keys.TRASH_RETENTION_DAYS] = days.coerceIn(1, 365) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private fun String.toThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(this) }.getOrDefault(ThemeMode.SYSTEM)

    private fun String.toLanding(): DefaultLanding =
        runCatching { DefaultLanding.valueOf(this) }.getOrDefault(DefaultLanding.TRANSACTIONS)

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SALARY_DAY = intPreferencesKey("salary_cycle_start_day")
        val DEFAULT_LANDING = stringPreferencesKey("default_landing")
        val CARRY_FORWARD = booleanPreferencesKey("carry_forward_enabled")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
    }
}
