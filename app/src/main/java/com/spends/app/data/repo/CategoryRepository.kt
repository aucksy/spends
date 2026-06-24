package com.spends.app.data.repo

import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.category.IconAssigner
import com.spends.app.data.db.dao.CategoryDao
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.domain.model.CategoryUsage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a delete request: a category in use can't be hard-deleted, so it's archived instead. */
enum class CategoryDeleteResult { DELETED, ARCHIVED }

@Singleton
class CategoryRepository @Inject constructor(
    private val dao: CategoryDao,
) {
    fun observeActive(): Flow<List<CategoryEntity>> = dao.observeActive()

    fun observeAll(): Flow<List<CategoryEntity>> = dao.observeAll()

    suspend fun getById(id: Long): CategoryEntity? = dao.getById(id)

    /** Create a custom category with an auto icon + a distinct auto color (PRD §4.4). */
    suspend fun addCustom(
        name: String,
        usage: CategoryUsage = CategoryUsage.EXPENSE,
        excludeFromSpend: Boolean = false,
    ): Long {
        val trimmed = name.trim()
        val taken = dao.allColors().toSet()
        val category = CategoryEntity(
            name = trimmed,
            iconKey = IconAssigner.keyFor(trimmed),
            colorHex = ColorAssigner.colorFor(trimmed, taken),
            isCustom = true,
            excludeFromSpend = excludeFromSpend,
            sortOrder = 1000,
            usage = usage,
        )
        return dao.insert(category)
    }

    suspend fun rename(id: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) dao.rename(id, trimmed)
    }

    suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived)

    suspend fun setExcludeFromSpend(category: CategoryEntity, exclude: Boolean) =
        dao.update(category.copy(excludeFromSpend = exclude))

    /**
     * Delete a category. If any transaction still references it (FK would block a hard delete),
     * archive it instead so history stays intact. Returns which action happened.
     */
    suspend fun deleteOrArchive(id: Long): CategoryDeleteResult =
        if (dao.allocationCount(id) > 0) {
            dao.setArchived(id, true)
            CategoryDeleteResult.ARCHIVED
        } else {
            dao.deleteById(id)
            CategoryDeleteResult.DELETED
        }
}
