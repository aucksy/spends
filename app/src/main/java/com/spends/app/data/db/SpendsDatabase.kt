package com.spends.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spends.app.data.db.dao.CategoryDao
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.db.dao.IgnoredPatternDao
import com.spends.app.data.db.dao.MerchantCategoryDao
import com.spends.app.data.db.dao.PaymentMethodDao
import com.spends.app.data.db.dao.PendingCaptureDao
import com.spends.app.data.db.dao.RecurringDao
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.db.entity.IgnoredPatternEntity
import com.spends.app.data.db.entity.MerchantCategoryEntity
import com.spends.app.data.db.entity.PaymentMethodEntity
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
        MerchantCategoryEntity::class,
        PaymentMethodEntity::class,
        IgnoredPatternEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SpendsDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun recurringDao(): RecurringDao
    abstract fun pendingCaptureDao(): PendingCaptureDao
    abstract fun merchantCategoryDao(): MerchantCategoryDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun ignoredPatternDao(): IgnoredPatternDao

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

        /** v5 -> v6: Investments & Loan/EMI become normal spend categories (BAU) — clear their
         *  excludeFromSpend flag on existing installs so they count in spend charts and lose the
         *  "Excluded from spend charts" label. No schema change (UPDATE only). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE categories SET excludeFromSpend = 0 WHERE name IN ('Investments', 'Loan/EMI')",
                )
            }
        }

        /** v6 -> v7: add the learned merchant→category table (#14). DDL must mirror Room's generated
         *  schema for [MerchantCategoryEntity] exactly so validation passes. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merchant_categories` (" +
                        "`merchantKey` TEXT NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`merchantKey`))",
                )
            }
        }

        /** v7 -> v8: store the original SMS body + sender on pending_captures so the review card can
         *  show the source text (#10) and search can match any value (#12). Both are nullable
         *  String? -> `TEXT` (no NOT NULL, no default), matching Room's generated DDL exactly. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_captures ADD COLUMN rawBody TEXT")
                db.execSQL("ALTER TABLE pending_captures ADD COLUMN sender TEXT")
            }
        }

        /** v8 -> v9: add `iconCustomized` so a hand-picked category icon (#5) sticks and isn't overwritten
         *  by the launch-time auto re-icon. Boolean -> INTEGER NOT NULL DEFAULT 0, matching Room's
         *  generated DDL exactly (and the entity's @ColumnInfo defaultValue) so validation passes. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN iconCustomized INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v9 -> v10: add the `payment_methods` table for the Smart Cycle / Cards feature (PRD §4.7).
         *  DDL must mirror Room's generated schema for [PaymentMethodEntity] exactly (column order =
         *  declaration order; autoGenerate Long PK → `PRIMARY KEY(id)`, no AUTOINCREMENT; nullable fields
         *  omit NOT NULL) so validation passes. `expenses.paymentMethodId` already exists (added with the
         *  table from the start), so no change is needed on the expenses side. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `payment_methods` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`institution` TEXT, " +
                        "`last4` TEXT, " +
                        "`colorHex` TEXT NOT NULL, " +
                        "`billingDay` INTEGER, " +
                        "`dueDay` INTEGER, " +
                        "`reviewed` INTEGER NOT NULL, " +
                        "`dismissed` INTEGER NOT NULL, " +
                        "`firstSeenAt` INTEGER NOT NULL, " +
                        "`lastActivityAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /** v10 -> v11: link auto-created transactions to their recurring rule (`expenses.recurringRuleId`,
         *  nullable → `INTEGER`, no NOT NULL, matching Room for `Long?`) for "edit all past" (#5); and add a
         *  per-rule occurrence cap (`recurring_rules.occurrenceLimit`, `INTEGER NOT NULL DEFAULT 0`, matching
         *  the entity's @ColumnInfo(defaultValue = "0")) for "repeat N times" (#8). */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN recurringRuleId INTEGER")
                db.execSQL("ALTER TABLE recurring_rules ADD COLUMN occurrenceLimit INTEGER NOT NULL DEFAULT 0")
                // Learn-from-ignore counts (#7). DDL mirrors Room's schema for [IgnoredPatternEntity] (String
                // PK → TEXT NOT NULL ... PRIMARY KEY, Int/Long → INTEGER NOT NULL).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ignored_patterns` (" +
                        "`patternKey` TEXT NOT NULL, " +
                        "`ignoreCount` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`patternKey`))",
                )
            }
        }
    }
}
