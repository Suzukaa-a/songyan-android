package app.songyan.ime.settings.ui

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardNumericMarks
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.KeyboardPageChrome
import app.songyan.ime.layout.KeyboardPageDefinition
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardSymbolPresentation
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutResourceBudget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutRowsEditing
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.layout.PhoneKeypadMetrics
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import app.songyan.ime.layout.PhoneKeypadStructure

/** The first key an editor selects, or null when there is none. */
private fun firstKey(rows: List<LayoutRow>): KeySelection? =
    rows.indexOfFirst { it.keys.isNotEmpty() }.takeIf { it >= 0 }?.let { KeySelection(it, 0) }

/**
 * A main layout being edited. Nothing is written until it is saved.
 *
 * The control row (the row with the space bar) is drawn by the keyboard from the editor and the
 * language, so the editor shows and edits the letter rows only and keeps the control row as it is.
 * A nine-key layout is a fixed 3x3 grid: its keys can be edited but not added, removed or moved, and
 * its side columns are edited as data once declared ([RailEditing]); everything below the grid is the
 * control row and stays as it was.
 */
data class LayoutDraft(
    val original: LayoutDefinition,
    val layout: LayoutDefinition = original,
    val selection: KeySelection? = firstKey(mainRowsOf(original)),
    /** The selected side-column key; at most one of this and [selection] is set. */
    val rail: RailSelection? = null,
) {
    /** The layout is a telephone keypad the nine-key shell draws. */
    val isNineKey: Boolean get() = PhoneKeypadStructure.matches(original)

    /** The rows the user edits. */
    val letterRows: List<LayoutRow> get() = mainRowsOf(layout, isNineKey)

    private val controlRows: List<LayoutRow>
        get() =
            if (isNineKey) {
                layout.rows.drop(
                    PhoneKeypadMetrics.MAIN_ROW_COUNT,
                )
            } else {
                layout.rows.filter(::isControlRow)
            }

    val hasChanges: Boolean get() = layout != original

    val selectedKey: LayoutKey?
        get() =
            rail?.let { RailEditing.keys(layout, it.position).getOrNull(it.index) }
                ?: selection?.let { LayoutRowsEditing.key(letterRows, it.row, it.key) }

    /** The side columns are still the keyboard's own; declaring them turns them into editable keys. */
    val usesBundledRails: Boolean get() = PhoneKeypadRailPolicy.usesBundledRails(layout)

    fun railKeys(position: LayoutAuxiliaryArea.Position): List<LayoutKey> = RailEditing.keys(layout, position)

    fun select(at: KeySelection): LayoutDraft =
        copy(
            selection =
                at.takeIf {
                    LayoutRowsEditing.key(letterRows, it.row, it.key) !=
                        null
                },
            rail = null,
        )

    fun selectRail(at: RailSelection): LayoutDraft =
        if (at.index in railKeys(at.position).indices) copy(rail = at, selection = null) else this

    fun rename(name: String): LayoutDraft = copy(layout = layout.renamed(name))

    fun replaceSelectedKey(key: LayoutKey): LayoutDraft {
        rail?.let { return copy(layout = RailEditing.replace(layout, it, key)) }
        val at = selection ?: return this
        return withRows(KeyGridEditing.replaceKey(letterRows, at, key))
    }

    /** Applies a structural edit of the grid; null (an edit that could not finish) changes nothing. */
    fun apply(edit: GridEdit?): LayoutDraft =
        if (edit ==
            null
        ) {
            this
        } else {
            withRows(edit.rows).copy(selection = edit.selection, rail = null)
        }

    fun withSplit(split: LayoutSplitConfiguration?): LayoutDraft =
        copy(layout = layout.withSplit(split?.let { LayoutRowsEditing.adjusted(it, layout.rows) }))

    /** Turns the keyboard's own side columns into keys the user can edit; the layout looks the same. */
    fun declareRails(): LayoutDraft = copy(layout = PhoneKeypadRailPolicy.withDeclaredRails(layout), rail = null)

    /** Gives the side columns back to the keyboard. The keys the user declared are dropped. */
    fun useBundledRails(): LayoutDraft =
        copy(layout = layout.copy(auxiliaryAreas = BuiltinLayouts.phone9.auxiliaryAreas), rail = null)

    fun addRailKey(
        position: LayoutAuxiliaryArea.Position,
        template: LayoutKey,
    ): LayoutDraft =
        RailEditing.add(layout, position, template)?.let { (next, at) ->
            copy(layout = next, rail = at, selection = null)
        }
            ?: this

    fun removeSelectedRailKey(): LayoutDraft {
        val at = rail ?: return this
        return RailEditing.remove(layout, at)?.let { (next, selected) -> copy(layout = next, rail = selected) } ?: this
    }

    fun moveSelectedRailKey(delta: Int): LayoutDraft {
        val at = rail ?: return this
        return RailEditing.move(layout, at, delta)?.let { (next, selected) -> copy(layout = next, rail = selected) }
            ?: this
    }

    private fun withRows(rows: List<LayoutRow>): LayoutDraft {
        val all = rows + controlRows
        val split = layout.split?.let { LayoutRowsEditing.adjusted(it, all) }
        return copy(layout = layout.copy(rows = all, split = split))
    }

    companion object {
        private fun isControlRow(row: LayoutRow) = row.keys.any { it.action.type == LayoutActionKind.SPACE }

        private fun mainRowsOf(
            layout: LayoutDefinition,
            nineKey: Boolean = PhoneKeypadStructure.matches(layout),
        ) = if (nineKey) {
            layout.rows.take(PhoneKeypadMetrics.MAIN_ROW_COUNT)
        } else {
            layout.rows.filterNot(::isControlRow)
        }
    }
}

/**
 * A page being edited: its content rows, or the categories of a categorized symbol page. The footer
 * and the rails the page carries are kept as they are; the keyboard draws them as data.
 */
data class PageDraft(
    val original: KeyboardPageDefinition,
    val page: KeyboardPageDefinition = original,
    val selection: KeySelection? = firstKey(original.rows),
    /** The open category of a categorized page and the selected key of it. */
    val categoryIndex: Int = 0,
    val categoryKey: Int = 0,
    /** The selected side-column key; at most one of this and [selection] is set. */
    val rail: RailSelection? = null,
) {
    val rows: List<LayoutRow> get() = page.rows

    /**
     * Side columns are authored on a page the keyboard draws from rows. The categorized page is drawn
     * by the symbol browser, which owns its own navigation column.
     */
    val allowsRails: Boolean get() = !isCategorized

    fun railKeys(position: LayoutAuxiliaryArea.Position): List<LayoutKey> =
        page.chrome?.let { if (position == LayoutAuxiliaryArea.Position.LEADING) it.leading else it.trailing }.orEmpty()

    val trailingSpansBottom: Boolean get() = page.chrome?.trailingSpansBottom == true

    fun railWidth(position: LayoutAuxiliaryArea.Position): Double? =
        page.chrome?.let { if (position == LayoutAuxiliaryArea.Position.LEADING) it.leadingWidth else it.trailingWidth }

    /** The page is the categorized symbol page with categories of its own, which the editor edits instead of rows. */
    val isCategorized: Boolean get() = original.isCategorizedWithCategories

    val categories: List<KeyboardPageCategory> get() = page.categories.orEmpty()

    val category: KeyboardPageCategory? get() = categories.getOrNull(categoryIndex)

    val selectedCategoryKey: LayoutKey? get() = category?.keys?.getOrNull(categoryKey)

    val hasChanges: Boolean get() = page != original

    val selectedKey: LayoutKey?
        get() =
            rail?.let { railKeys(it.position).getOrNull(it.index) }
                ?: selection?.let { LayoutRowsEditing.key(rows, it.row, it.key) }

    fun select(at: KeySelection): PageDraft =
        copy(
            selection =
                at.takeIf {
                    LayoutRowsEditing.key(rows, it.row, it.key) !=
                        null
                },
            rail = null,
        )

    fun rename(name: String): PageDraft = copy(page = page.withName(name))

    /** The page owns bilingual rows, so it has a branch to open in; the categorized page has no marks of its own. */
    val choosesOpeningMarks: Boolean
        get() =
            page.kind == KeyboardPageKind.NUMERIC ||
                (
                    page.kind == KeyboardPageKind.SYMBOLS &&
                        !isCategorized &&
                        page.symbolPresentation != KeyboardSymbolPresentation.CATEGORIZED
                )

    /** The branch the page opens in; null follows the input language (English input opens the English marks). */
    val openingMarks: KeyboardNumericMarks? get() = page.defaultSymbolMarks

    fun withOpeningMarks(marks: KeyboardNumericMarks?): PageDraft = copy(page = page.withDefaultSymbolMarks(marks))

    fun replaceSelectedKey(key: LayoutKey): PageDraft {
        rail?.let { at ->
            val chrome = page.chrome ?: return this
            val region = at.position.region()
            val replaced = chrome.replacingKey(region, at.index, key) ?: return this
            return copy(page = page.withChrome(replaced))
        }
        val at = selection ?: return this
        return withRows(KeyGridEditing.replaceKey(rows, at, key))
    }

    fun apply(edit: GridEdit?): PageDraft =
        if (edit ==
            null
        ) {
            this
        } else {
            withRows(edit.rows).copy(selection = edit.selection, rail = null)
        }

    fun withSplit(split: LayoutSplitConfiguration?): PageDraft =
        copy(page = page.withSplit(split?.let { LayoutRowsEditing.adjusted(it, page.rows) }))

    private fun withRows(rows: List<LayoutRow>): PageDraft {
        val split = page.split?.let { LayoutRowsEditing.adjusted(it, rows) }
        return copy(page = page.withRows(rows).withSplit(split))
    }

    // Side columns.

    /**
     * The page with explicit chrome. A page stored without it is drawn from the built-in footer, so
     * the data the keyboard draws is written out first and the page looks the same.
     */
    private fun withExplicitChrome(): PageDraft = if (page.chrome != null) this else copy(page = page.materialized())

    private fun withRailKeys(
        position: LayoutAuxiliaryArea.Position,
        keys: List<LayoutKey>,
    ): PageDraft {
        val chrome = page.chrome ?: return this
        return copy(
            page =
                page.withChrome(
                    if (position == LayoutAuxiliaryArea.Position.LEADING) {
                        chrome.copy(leading = keys)
                    } else {
                        chrome.copy(trailing = keys)
                    },
                ),
        )
    }

    fun selectRail(at: RailSelection): PageDraft =
        if (at.index in railKeys(at.position).indices) copy(rail = at, selection = null) else this

    fun addRailKey(
        position: LayoutAuxiliaryArea.Position,
        template: LayoutKey,
    ): PageDraft {
        if (!allowsRails) return this
        val base = withExplicitChrome()
        val keys = base.railKeys(position)
        if (keys.size >= LayoutResourceBudget.KEYS_PER_REGION) return this
        return base
            .withRailKeys(
                position,
                keys + template,
            ).copy(rail = RailSelection(position, keys.size), selection = null)
    }

    fun removeSelectedRailKey(): PageDraft {
        val at = rail ?: return this
        val keys = railKeys(at.position)
        if (at.index !in keys.indices) return this
        val next = keys.filterIndexed { index, _ -> index != at.index }
        val selected = if (next.isEmpty()) null else RailSelection(at.position, minOf(at.index, next.lastIndex))
        return withRailKeys(at.position, next).copy(rail = selected)
    }

    fun moveSelectedRailKey(delta: Int): PageDraft {
        val at = rail ?: return this
        val keys = railKeys(at.position)
        val target = at.index + delta
        if (at.index !in keys.indices || target !in keys.indices) return this
        val moved = keys.toMutableList()
        moved[at.index] = keys[target]
        moved[target] = keys[at.index]
        return withRailKeys(at.position, moved).copy(rail = RailSelection(at.position, target))
    }

    /** A null width follows the keyboard's own rail width. */
    fun setRailWidth(
        position: LayoutAuxiliaryArea.Position,
        width: Double?,
    ): PageDraft {
        val chrome = page.chrome ?: return this
        return copy(
            page =
                page.withChrome(
                    if (position == LayoutAuxiliaryArea.Position.LEADING) {
                        chrome.copy(leadingWidth = width)
                    } else {
                        chrome.copy(trailingWidth = width)
                    },
                ),
        )
    }

    fun setTrailingSpansBottom(value: Boolean): PageDraft {
        val chrome = page.chrome ?: return this
        return copy(page = page.withChrome(chrome.copy(trailingSpansBottom = value)))
    }

    // Categories.

    private fun withCategories(
        categories: List<KeyboardPageCategory>,
        index: Int = categoryIndex,
        key: Int = 0,
    ): PageDraft =
        copy(
            page = page.withCategories(categories),
            categoryIndex = index.coerceIn(0, maxOf(0, categories.lastIndex)),
            categoryKey = key,
        )

    fun selectCategory(index: Int): PageDraft =
        if (index in categories.indices) copy(categoryIndex = index, categoryKey = 0) else this

    fun selectCategoryKey(index: Int): PageDraft =
        if (category?.keys?.indices?.contains(index) == true) copy(categoryKey = index) else this

    fun addCategory(
        id: String,
        name: String,
    ): PageDraft =
        CategoryEditing.add(categories, id, name, CATEGORY_KEY)?.let { withCategories(it, it.lastIndex) } ?: this

    fun duplicateCategory(
        id: String,
        name: String,
    ): PageDraft =
        CategoryEditing.duplicate(categories, categoryIndex, id, name)?.let { withCategories(it, categoryIndex + 1) }
            ?: this

    fun removeCategory(): PageDraft =
        CategoryEditing
            .remove(
                categories,
                categoryIndex,
            )?.let { withCategories(it, minOf(categoryIndex, it.lastIndex)) }
            ?: this

    fun moveCategory(delta: Int): PageDraft =
        CategoryEditing.move(categories, categoryIndex, delta)?.let { withCategories(it, categoryIndex + delta) }
            ?: this

    fun renameCategory(name: String): PageDraft =
        withCategories(CategoryEditing.replace(categories, categoryIndex) { it.withName(name) }, key = categoryKey)

    fun setCategoryColumns(columns: Int): PageDraft =
        withCategories(
            CategoryEditing.replace(categories, categoryIndex) { CategoryEditing.withColumns(it, columns) },
            key = categoryKey,
        )

    fun setCategorySource(source: KeyboardPageCategory.Source): PageDraft =
        withCategories(
            CategoryEditing.replace(categories, categoryIndex) { CategoryEditing.withSource(it, source, CATEGORY_KEY) },
        )

    fun addCategoryKey(): PageDraft {
        val current = category ?: return this
        val (next, at) = CategoryEditing.addKey(current, categoryKey, CATEGORY_KEY) ?: return this
        return withCategories(CategoryEditing.replace(categories, categoryIndex) { next }, key = at)
    }

    fun removeCategoryKey(): PageDraft {
        val current = category ?: return this
        val (next, at) = CategoryEditing.removeKey(current, categoryKey) ?: return this
        return withCategories(CategoryEditing.replace(categories, categoryIndex) { next }, key = at)
    }

    fun moveCategoryKey(delta: Int): PageDraft {
        val current = category ?: return this
        val (next, at) = CategoryEditing.moveKey(current, categoryKey, delta) ?: return this
        return withCategories(CategoryEditing.replace(categories, categoryIndex) { next }, key = at)
    }

    fun replaceSelectedCategoryKey(key: LayoutKey): PageDraft =
        withCategories(
            CategoryEditing.replace(categories, categoryIndex) { CategoryEditing.replaceKey(it, categoryKey, key) },
            key = categoryKey,
        )

    private companion object {
        fun LayoutAuxiliaryArea.Position.region(): KeyboardPageChrome.Region =
            if (this == LayoutAuxiliaryArea.Position.LEADING) {
                KeyboardPageChrome.Region.LEADING
            } else {
                KeyboardPageChrome.Region.TRAILING
            }

        val CATEGORY_KEY =
            LayoutKey(
                "。",
                action =
                    app.songyan.ime.layout.LayoutAction
                        .literal("。"),
            )
    }
}
