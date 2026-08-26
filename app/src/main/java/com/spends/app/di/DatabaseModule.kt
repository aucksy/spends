package com.spends.app.di

import android.content.Context
import androidx.room.Room
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.db.dao.CategoryDao
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.db.dao.IgnoredPatternDao
import com.spends.app.data.db.dao.MerchantCategoryDao
import com.spends.app.data.db.dao.PaymentMethodDao
import com.spends.app.data.db.dao.PendingCaptureDao
import com.spends.app.data.db.dao.RecurringDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * The single Room instance for this process.
     *
     * **Demo mode swaps the FILE, not the contents.** When [DemoMode.isEnabled] the app opens
     * `spends-demo.db`; the live `spends.db` is then never opened at all for the life of the process — not
     * read, not written, not migrated. That is the whole safety story behind demo mode, and it lives in this
     * one expression. The flag is read synchronously here because a Hilt provider cannot suspend; flipping it
     * restarts the process (see [DemoMode.restartInto]) so this is evaluated exactly once per mode.
     *
     * A fresh demo database is created straight at the current schema version, so the migration list is inert
     * for it; the seed callback still runs, which is what gives the demo its standard category set.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SpendsDatabase =
        Room.databaseBuilder(
            context,
            SpendsDatabase::class.java,
            if (DemoMode.isEnabled(context)) DemoMode.DEMO_DB_NAME else SpendsDatabase.NAME,
        )
            .addCallback(SpendsDatabase.SEED_CALLBACK)
            .addMigrations(
                SpendsDatabase.MIGRATION_1_2,
                SpendsDatabase.MIGRATION_2_3,
                SpendsDatabase.MIGRATION_3_4,
                SpendsDatabase.MIGRATION_4_5,
                SpendsDatabase.MIGRATION_5_6,
                SpendsDatabase.MIGRATION_6_7,
                SpendsDatabase.MIGRATION_7_8,
                SpendsDatabase.MIGRATION_8_9,
                SpendsDatabase.MIGRATION_9_10,
                SpendsDatabase.MIGRATION_10_11,
                SpendsDatabase.MIGRATION_11_12,
                SpendsDatabase.MIGRATION_12_13,
                SpendsDatabase.MIGRATION_13_14,
                SpendsDatabase.MIGRATION_14_15,
                SpendsDatabase.MIGRATION_15_16,
                SpendsDatabase.MIGRATION_16_17,
            )
            .build()

    @Provides
    fun provideCategoryDao(db: SpendsDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideExpenseDao(db: SpendsDatabase): ExpenseDao = db.expenseDao()

    @Provides
    fun provideRecurringDao(db: SpendsDatabase): RecurringDao = db.recurringDao()

    @Provides
    fun providePendingCaptureDao(db: SpendsDatabase): PendingCaptureDao = db.pendingCaptureDao()

    @Provides
    fun provideMerchantCategoryDao(db: SpendsDatabase): MerchantCategoryDao = db.merchantCategoryDao()

    @Provides
    fun providePaymentMethodDao(db: SpendsDatabase): PaymentMethodDao = db.paymentMethodDao()

    @Provides
    fun provideIgnoredPatternDao(db: SpendsDatabase): IgnoredPatternDao = db.ignoredPatternDao()
}
