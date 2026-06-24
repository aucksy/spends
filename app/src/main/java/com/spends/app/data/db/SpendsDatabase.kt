package com.spends.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spends.app.data.db.dao.CategoryDao
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.seed.CategorySeed

@Database(
    entities = [
        CategoryEntity::class,
        ExpenseEntity::class,
        AllocationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SpendsDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao

    companion object {
        const val NAME = "spends.db"

        private const val INSERT_CATEGORY =
            "INSERT INTO categories " +
                "(name, iconKey, colorHex, isCustom, isArchived, excludeFromSpend, sortOrder, usage) " +
                "VALUES (?, ?, ?, 0, 0, ?, ?, ?)"

        /** Seeds all prebuilt categories (expense + income) on first creation. */
        val SEED_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                for (row in CategorySeed.allRows()) {
                    db.execSQL(
                        INSERT_CATEGORY,
                        arrayOf<Any>(
                            row.name,
                            row.iconKey,
                            row.colorHex,
                            if (row.excludeFromSpend) 1 else 0,
                            row.sortOrder,
                            row.usage.name,
                        ),
                    )
                }
            }
        }

        /** v1 -> v2: add the category `usage` column and seed the income categories. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN usage TEXT NOT NULL DEFAULT 'EXPENSE'")
                for (row in CategorySeed.incomeRows()) {
                    db.execSQL(
                        INSERT_CATEGORY,
                        arrayOf<Any>(
                            row.name,
                            row.iconKey,
                            row.colorHex,
                            if (row.excludeFromSpend) 1 else 0,
                            row.sortOrder,
                            row.usage.name,
                        ),
                    )
                }
            }
        }
    }
}
