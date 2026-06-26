package com.spends.app.di

import android.content.Context
import androidx.room.Room
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.dao.CategoryDao
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.db.dao.MerchantCategoryDao
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SpendsDatabase =
        Room.databaseBuilder(context, SpendsDatabase::class.java, SpendsDatabase.NAME)
            .addCallback(SpendsDatabase.SEED_CALLBACK)
            .addMigrations(
                SpendsDatabase.MIGRATION_1_2,
                SpendsDatabase.MIGRATION_2_3,
                SpendsDatabase.MIGRATION_3_4,
                SpendsDatabase.MIGRATION_4_5,
                SpendsDatabase.MIGRATION_5_6,
                SpendsDatabase.MIGRATION_6_7,
                SpendsDatabase.MIGRATION_7_8,
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
}
