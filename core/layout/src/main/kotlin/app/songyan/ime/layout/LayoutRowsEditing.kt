package app.songyan.ime.layout

/**
 * Structural edits of a list of key rows, shared by the main layout editor and the page editor.
 *
 * Every edit returns a new list, or null when it cannot finish, so a move that fails halfway never
 * loses a key. Drafts may hold invalid keys (an empty label while typing); saving validates the whole
 * draft, not these edits.
 */
object LayoutRowsEditing {
    fun key(
        rows: List<LayoutRow>,
        row: Int,
        key: Int,
    ): LayoutKey? = rows.getOrNull(row)?.keys?.getOrNull(key)

    /** A layout keeps at least one row with one key, so the only key left stays. */
    fun canRemoveKey(
        rows: List<LayoutRow>,
        row: Int,
        key: Int,
    ): Boolean = key(rows, row, key) != null && (rows[row].keys.size > 1 || rows.size > 1)

    /** Removes one key; a row left without keys goes with it. */
    fun removeKey(
        rows: List<LayoutRow>,
        row: Int,
        key: Int,
    ): List<LayoutRow>? {
        if (!canRemoveKey(rows, row, key)) return null
        val keys = rows[row].keys.toMutableList().also { it.removeAt(key) }
        return if (keys.isEmpty()) rows.filterIndexed { index, _ -> index != row } else replaceRow(rows, row, keys)
    }

    /** Inserts [new] at [index] of a row; null when the row is full or the address is wrong. */
    fun insertKey(
        rows: List<LayoutRow>,
        row: Int,
        index: Int,
        new: LayoutKey,
    ): List<LayoutRow>? {
        val keys = rows.getOrNull(row)?.keys ?: return null
        if (index !in 0..keys.size || keys.size >= LayoutResourceBudget.KEYS_PER_REGION) return null
        return replaceRow(rows, row, keys.toMutableList().also { it.add(index, new) })
    }

    /** Swaps a key with its neighbour in the row ([delta] is -1 for left, +1 for right). */
    fun moveKey(
        rows: List<LayoutRow>,
        row: Int,
        key: Int,
        delta: Int,
    ): List<LayoutRow>? {
        val keys = rows.getOrNull(row)?.keys ?: return null
        val target = key + delta
        if (key !in keys.indices || target !in keys.indices) return null
        val moved = keys.toMutableList()
        moved[key] = keys[target]
        moved[target] = keys[key]
        return replaceRow(rows, row, moved)
    }

    /** Appends a row holding [first]; null at the row limit. */
    fun addRow(
        rows: List<LayoutRow>,
        first: LayoutKey,
    ): List<LayoutRow>? = if (rows.size >= LayoutResourceBudget.ROWS) null else rows + LayoutRow(listOf(first))

    /** Swaps a row with its neighbour ([delta] is -1 for up, +1 for down). */
    fun moveRow(
        rows: List<LayoutRow>,
        row: Int,
        delta: Int,
    ): List<LayoutRow>? {
        val target = row + delta
        if (row !in rows.indices || target !in rows.indices) return null
        val moved = rows.toMutableList()
        moved[row] = rows[target]
        moved[target] = rows[row]
        return moved
    }

    /**
     * Stores a cut for every row, clamped to the row's current key count, so adding or removing keys
     * never leaves a cut outside its row.
     */
    fun adjusted(
        split: LayoutSplitConfiguration,
        rows: List<LayoutRow>,
    ): LayoutSplitConfiguration = split.copy(rowBreaks = rows.indices.map { split.breakIndex(it, rows[it].keys.size) })

    private fun replaceRow(
        rows: List<LayoutRow>,
        row: Int,
        keys: List<LayoutKey>,
    ): List<LayoutRow> = rows.mapIndexed { index, existing -> if (index == row) LayoutRow(keys) else existing }
}
