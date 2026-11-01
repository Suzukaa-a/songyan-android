package app.songyan.ime.layout

/**
 * Stable structural address of one page key. The page editor, preview and budget all use the same
 * address instead of exposing separate row/footer/rail index spaces.
 */
sealed class KeyboardPageKeyPath {
    data class Row(
        val row: Int,
        val key: Int,
    ) : KeyboardPageKeyPath()

    data class Chrome(
        val region: KeyboardPageChrome.Region,
        val key: Int,
    ) : KeyboardPageKeyPath()
}

/**
 * Every key the page really carries: rows, category content and authored chrome. Budgets and
 * render invalidation must not miss a category's top-level Enter or an authored footer key.
 */
val KeyboardPageDefinition.allKeys: List<LayoutKey>
    get() = rows.flatMap { it.keys } + (categories ?: emptyList()).flatMap { it.keys } + chromeKeys

/** Authored function keys only; rows and categories may contain controls too. */
val KeyboardPageDefinition.chromeKeys: List<LayoutKey>
    get() = chrome?.allKeys ?: emptyList()

fun KeyboardPageDefinition.keyAt(path: KeyboardPageKeyPath): LayoutKey? =
    when (path) {
        is KeyboardPageKeyPath.Row ->
            rows.getOrNull(path.row)?.keys?.getOrNull(path.key)
        is KeyboardPageKeyPath.Chrome ->
            chrome?.keys(path.region)?.getOrNull(path.key)
    }

/**
 * Draft mutation deliberately allows invalid intermediate values; saving validates the whole draft.
 * An address that no longer exists is a no-op, mirroring [LayoutDefinition.replacingKey].
 */
fun KeyboardPageDefinition.replacingKeyAt(
    path: KeyboardPageKeyPath,
    key: LayoutKey,
): KeyboardPageDefinition {
    if (keyAt(path) == null) return this
    return when (path) {
        is KeyboardPageKeyPath.Row -> {
            val newRows = rows.toMutableList()
            val keys = newRows[path.row].keys.toMutableList()
            keys[path.key] = key
            newRows[path.row] = LayoutRow(keys)
            copy(rows = newRows)
        }
        is KeyboardPageKeyPath.Chrome -> {
            val chrome = chrome ?: return this
            val updated = chrome.replacingKey(path.region, path.key, key) ?: return this
            copy(chrome = updated)
        }
    }
}
