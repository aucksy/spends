package com.spends.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.spends.app.data.demo.DemoMode
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The preferences store behind [com.spends.app.data.settings.SettingsRepository].
 *
 * This used to be a `by preferencesDataStore(name = "settings")` property delegate on `Context`. It moved here
 * so **demo mode can swap the file** the same way it swaps the Room database — demo settings (salary day,
 * Smart Cycle, carry-forward, the AI toggles) live in their own file and can never write over the real ones.
 *
 * ⚠ The live name must stay exactly `"settings"`: `preferencesDataStore(name = n)` and
 * `Context.preferencesDataStoreFile(n)` both resolve to `filesDir/datastore/$n.preferences_pb`, so keeping the
 * name identical means every existing install picks up its existing preferences file unchanged. Renaming it
 * would silently reset everyone's settings to defaults.
 *
 * `@Singleton` is load-bearing: DataStore throws if two live instances are created over one file.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(
                if (DemoMode.isEnabled(context)) DemoMode.DEMO_SETTINGS_NAME else DemoMode.LIVE_SETTINGS_NAME,
            )
        }
}
