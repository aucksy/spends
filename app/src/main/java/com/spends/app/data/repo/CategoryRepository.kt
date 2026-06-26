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

    /** Active categories, most-used first (for the quick picker). */
    fun observeActiveByUsage(): Flow<List<CategoryEntity>> = dao.observeActiveByUsage()

    fun observeAll(): Flow<List<CategoryEntity>> = dao.observeAll()

    suspend fun getById(id: Long): CategoryEntity? = dao.getById(id)

    /**
     * Create a custom category with a distinct auto color (PRD §4.4). The icon is auto-assigned from the
     * name unless the caller passes a hand-picked [iconKey] (#5), in which case it's stored as customized
     * so the launch-time auto re-icon leaves it alone.
     */
    suspend fun addCustom(
        name: String,
        usage: CategoryUsage = CategoryUsage.EXPENSE,
        excludeFromSpend: Boolean = false,
        iconKey: String? = null,
    ): Long {
        val trimmed = name.trim()
        val taken = dao.allColors().toSet()
        val category = CategoryEntity(
            name = trimmed,
            iconKey = iconKey ?: IconAssigner.keyFor(trimmed),
            iconCustomized = iconKey != null,
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

    /** Apply an edit from the category editor (#5): rename (if non-blank) and set the icon. When the user
     *  hand-picked the icon it's marked customized so it sticks; otherwise it stays auto-managed. */
    suspend fun updateNameAndIcon(id: Long, newName: String, iconKey: String, iconCustomized: Boolean) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) dao.rename(id, trimmed)
        dao.setIconCustom(id, iconKey, iconCustomized)
    }

    /**
     * Re-derive the auto icon for every category from its name. Run on launch so categories created
     * by older imports (when the keyword rules were thinner) pick up the better-matching icons —
     * fixes "all imported categories look the same". Idempotent: only writes when the key changes.
     */
    suspend fun refreshAutoIcons() {
        dao.getAllOnce().forEach { cat ->
            // Never overwrite an icon the user hand-picked (#5) — only auto-managed ones get re-derived.
            if (cat.iconCustomized) return@forEach
            val key = IconAssigner.keyFor(cat.name)
            if (key != cat.iconKey) dao.updateIcon(cat.id, key)
        }
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
