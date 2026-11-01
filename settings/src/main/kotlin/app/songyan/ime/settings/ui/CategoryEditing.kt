package app.songyan.ime.settings.ui

import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutResourceBudget
import app.songyan.ime.layout.LayoutSplitConfiguration

/**
 * The structural edits of the categories of a categorized symbol page. Pure, so the rules (at least
 * one category stays, a recent tab keeps the keys it had, a column change keeps the cuts inside the
 * rows) are unit-tested; every edit returns null when it cannot finish.
 */
object CategoryEditing {
    fun add(
        categories: List<KeyboardPageCategory>,
        id: String,
        name: String,
        template: LayoutKey,
    ): List<KeyboardPageCategory>? =
        if (categories.size >= LayoutResourceBudget.PAGE_CATEGORIES) {
            null
        } else {
            categories + KeyboardPageCategory(id = id, name = name, keys = listOf(template))
        }

    /** A copy next to the original, with its own id; null at the category limit. */
    fun duplicate(
        categories: List<KeyboardPageCategory>,
        index: Int,
        id: String,
        name: String,
    ): List<KeyboardPageCategory>? {
        val original = categories.getOrNull(index) ?: return null
        if (categories.size >= LayoutResourceBudget.PAGE_CATEGORIES) return null
        return categories.take(index + 1) + original.copy(id = id, name = name) + categories.drop(index + 1)
    }

    /** The last category cannot go: a categorized page with none is refused on save. */
    fun remove(
        categories: List<KeyboardPageCategory>,
        index: Int,
    ): List<KeyboardPageCategory>? =
        if (index !in categories.indices || categories.size <= 1) {
            null
        } else {
            categories.filterIndexed { i, _ -> i != index }
        }

    fun move(
        categories: List<KeyboardPageCategory>,
        index: Int,
        delta: Int,
    ): List<KeyboardPageCategory>? {
        val target = index + delta
        if (index !in categories.indices || target !in categories.indices) return null
        val moved = categories.toMutableList()
        moved[index] = categories[target]
        moved[target] = categories[index]
        return moved
    }

    fun replace(
        categories: List<KeyboardPageCategory>,
        index: Int,
        change: (KeyboardPageCategory) -> KeyboardPageCategory,
    ): List<KeyboardPageCategory> =
        categories.mapIndexed { i, category -> if (i == index) change(category) else category }

    /** Columns 1 to the budget; the cuts of a split are brought back inside the new rows. */
    fun withColumns(
        category: KeyboardPageCategory,
        columns: Int,
    ): KeyboardPageCategory {
        val next = category.copy(columns = columns.coerceIn(1, LayoutResourceBudget.CATEGORY_COLUMNS))
        return next.copy(split = next.split?.let { adjusted(it, next) })
    }

    /** A recent category keeps its own keys, so switching back loses nothing; a fixed one with none gets one. */
    fun withSource(
        category: KeyboardPageCategory,
        source: KeyboardPageCategory.Source,
        template: LayoutKey,
    ): KeyboardPageCategory {
        val next = category.withSource(source)
        val filled =
            if (source == KeyboardPageCategory.Source.FIXED && next.keys.isEmpty()) {
                next.withKeys(listOf(template))
            } else {
                next
            }
        return filled.copy(split = filled.split?.let { adjusted(it, filled) })
    }

    fun addKey(
        category: KeyboardPageCategory,
        after: Int,
        template: LayoutKey,
    ): Pair<KeyboardPageCategory, Int>? {
        if (category.keys.size >= LayoutResourceBudget.CATEGORY_KEYS) return null
        val at = (after + 1).coerceIn(0, category.keys.size)
        val keys = category.keys.take(at) + template + category.keys.drop(at)
        return resized(category, keys) to at
    }

    /** The last key of a fixed category cannot go: it would be refused on save. */
    fun removeKey(
        category: KeyboardPageCategory,
        index: Int,
    ): Pair<KeyboardPageCategory, Int>? {
        if (index !in category.keys.indices || category.keys.size <= 1) return null
        val keys = category.keys.filterIndexed { i, _ -> i != index }
        return resized(category, keys) to minOf(index, keys.lastIndex)
    }

    fun moveKey(
        category: KeyboardPageCategory,
        index: Int,
        delta: Int,
    ): Pair<KeyboardPageCategory, Int>? {
        val target = index + delta
        if (index !in category.keys.indices || target !in category.keys.indices) return null
        val keys = category.keys.toMutableList()
        keys[index] = category.keys[target]
        keys[target] = category.keys[index]
        return category.withKeys(keys) to target
    }

    fun replaceKey(
        category: KeyboardPageCategory,
        index: Int,
        key: LayoutKey,
    ): KeyboardPageCategory =
        if (index !in category.keys.indices) {
            category
        } else {
            category.withKeys(category.keys.mapIndexed { i, old -> if (i == index) key else old })
        }

    private fun resized(
        category: KeyboardPageCategory,
        keys: List<LayoutKey>,
    ): KeyboardPageCategory {
        val next = category.withKeys(keys)
        return next.copy(split = next.split?.let { adjusted(it, next) })
    }

    private fun adjusted(
        split: LayoutSplitConfiguration,
        category: KeyboardPageCategory,
    ): LayoutSplitConfiguration {
        // A recent category's rows come from the recent allowance, not from its stored keys.
        val rows =
            if (category.source == KeyboardPageCategory.Source.RECENT) {
                List((LayoutResourceBudget.RECENT_CATEGORY_KEYS + category.columns - 1) / category.columns) {
                    category.columns
                }
            } else {
                category.rows.map { it.keys.size }
            }
        return split.copy(rowBreaks = rows.indices.map { split.breakIndex(it, rows[it]) })
    }
}
