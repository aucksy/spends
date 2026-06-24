package com.spends.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SpendsDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun expenseDao(): ExpenseDao

    companion object {
        const val NAME = "spends.db"

        /** Seeds the prebuilt categories when the database is first created. */
        val SEED_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                for (row in CategorySeed.rows()) {
                    db.execSQL(
                        "INSERT INTO categories " +
                            "(name, iconKey, colorHex, isCustom, isArchived, excludeFromSpend, sortOrder) " +
                            "VALUES (?, ?, ?, 0, 0, ?, ?)",
                        arrayOf<Any>(
                            row.name,
                            row.iconKey,
                            row.colorHex,
                            if (row.excludeFromSpend) 1 else 0,
                            row.sortOrder,
                        ),
                    )
                }
            }
        }
    }
}
