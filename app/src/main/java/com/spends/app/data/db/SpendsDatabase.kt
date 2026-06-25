package com.spends.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spends.app.data.db.dao.CategoryDao
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.db.dao.PendingCaptureDao
import com.spends.app.data.db.dao.RecurringDao
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.seed.CategorySeed

@Database(
    entities = [
        CategoryEntity::class,
        ExpenseEntity::class,
        AllocationEntity::class,
        RecurringRuleEntity::class,
        PendingCaptureEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SpendsDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun recurringDao(): RecurringDao
    abstract fun pendingCaptureDao(): PendingCaptureDao

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

        /** v2 -> v3: recolour/re-icon the prebuilt categories to the Design-System palette
         *  (custom categories keep their colours). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (row in CategorySeed.allRows()) {
                    db.execSQL(
                        "UPDATE categories SET colorHex = ?, iconKey = ? WHERE name = ? AND isCustom = 0",
                        arrayOf<Any>(row.colorHex, row.iconKey, row.name),
                    )
                }
            }
        }

        /** v3 -> v4: add the recurring-rules table (PRD §4.8). Column defs/indices must match the
         *  Room-generated schema for [RecurringRuleEntity] exactly so validation passes. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recurring_rules` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`amountMinor` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`merchant` TEXT, " +
                        "`note` TEXT, " +
                        "`frequency` TEXT NOT NULL, " +
                        "`intervalCount` INTEGER NOT NULL, " +
                        "`anchorDay` INTEGER NOT NULL, " +
                        "`startDate` INTEGER NOT NULL, " +
                        "`nextRunAt` INTEGER NOT NULL, " +
                        "`lastRunAt` INTEGER, " +
                        "`active` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_nextRunAt` ON `recurring_rules` (`nextRunAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_active` ON `recurring_rules` (`active`)")
            }
        }

        /** v4 -> v5: add the review-only `pending_captures` table (PRD §4.1). DDL must mirror Room's
         *  generated schema for [PendingCaptureEntity] exactly so validation passes. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_captures` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`amountMinor` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`merchant` TEXT, " +
                        "`last4` TEXT, " +
                        "`institution` TEXT, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`parseConfidence` INTEGER NOT NULL, " +
                        "`dedupeHash` TEXT NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_captures_dedupeHash` " +
                        "ON `pending_captures` (`dedupeHash`)",
                )
            }
        }
    }
}
