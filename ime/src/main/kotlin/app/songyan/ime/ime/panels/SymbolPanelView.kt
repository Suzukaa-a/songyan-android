package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.content.SymbolCategory
import app.songyan.ime.ime.R

private const val BOTTOM_SIDE_WEIGHT = 1.3
private const val BOTTOM_SPACE_WEIGHT = 2.5
private const val BOTTOM_SEARCH_WEIGHT = 2.0

/**
 * The symbol panel: a rail of catalog categories (plus a leading "recent" choice) and a grid of
 * symbols. Tapping a symbol inserts it through [onInsert]; recording the recent list stays with
 * the owner, which also owns the store.
 *
 * The catalog is pushed in with [update]; the view itself never reads the bundled or user file,
 * so the IME process keeps all disk work on its background loader.
 */
class SymbolPanelView(
    context: Context,
) : QuickContentPanelView(context) {
    /** Insert one symbol into the editor; the owner also records it as recently used. */
    var onInsert: (String) -> Unit = {}

    /** Delete the character before the caret (the bottom bar's ⌫). */
    var onBackspace: () -> Unit = {}

    /** The bottom bar's search key: the owner hands the keyboard over to type the query. */
    var onSearch: () -> Unit = {}

    /** Type a space (the bottom bar's space key). */
    var onSpace: () -> Unit = {}

    /** The empty state's action: the owner closes the panel and opens the settings screen. */
    var onOpenSettings: () -> Unit = {}

    private class SymbolCell(
        val text: String,
        val label: String,
    )

    private var catalog: SymbolCatalog = SymbolCatalog()
    private var recents: List<String> = emptyList()

    /** One entry per rail slot; null is the "recent" choice, always the first. */
    private var railCategories: List<SymbolCategory?> = emptyList()
    private var cells: List<SymbolCell> = emptyList()

    /** The open category, remembered by id: the recent slot appears and disappears as it fills. */
    private var selectedCategoryId: String? = null
    private var recentsSelected = true

    override val titleText: String get() = keyNames.chinese().getString(R.string.ime_panel_symbols_title)

    /**
     * Pushes the current catalog and recent symbols.
     *
     * Inserting a symbol refreshes the recent list, so this runs while the user is browsing: the open
     * category and both scroll offsets are kept, otherwise every insert would jump back to the start.
     */
    fun update(
        catalog: SymbolCatalog,
        recents: List<String>,
    ) {
        val browsing = captureBrowsingState()
        this.catalog = catalog
        this.recents = recents
        val labels = ArrayList<String>()
        val categories = ArrayList<SymbolCategory?>()
        // "Recent" is always the first slot, filled or not: a slot that appears after the first use
        // would push every category down and move the one the user is looking at.
        labels += keyNames.chinese().getString(R.string.ime_panel_recent)
        categories += null
        for (category in catalog.orderedCategories) {
            if (catalog.itemsIn(category.id).isEmpty()) continue
            labels += category.name
            categories += category
        }
        railCategories = categories
        val wanted = SymbolRailSelection.reopenIndex(railCategories, recentsSelected, selectedCategoryId)
        updateRail(
            labels,
            PanelBrowsingState(
                rail = wanted,
                railScrollDp = browsing.railScrollDp,
                contentScrollDp = browsing.contentScrollDp,
            ),
        )
        loadCells(wanted, keepScroll = true)
    }

    /**
     * Pushes a new recent list after a use, without the catalog and the rail being read again.
     *
     * The rail cannot change by a use (the recent slot is always first), so only the cells are
     * rebuilt, and only while the recent slot is open: a category the user is browsing keeps its
     * cells and both scroll offsets, so tapping a symbol never moves the grid under the finger.
     */
    fun updateRecents(recents: List<String>) {
        this.recents = recents
        if (!recentsSelected) return
        cells = recents.map { SymbolCell(text = it, label = it) }
        invalidate()
    }

    /**
     * A hidden panel holds no catalog: an [update] fills the rails and cells again and reopens the
     * category remembered by [selectedCategoryId] (or the recent slot, which has no id).
     */
    override fun releaseData() {
        super.releaseData()
        catalog = SymbolCatalog()
        recents = emptyList()
        railCategories = emptyList()
        cells = emptyList()
        updateRail(emptyList())
        invalidate()
    }

    private fun reloadCells(railIndex: Int) = loadCells(railIndex, keepScroll = false)

    private fun loadCells(
        railIndex: Int,
        keepScroll: Boolean,
    ) {
        val category = railCategories.getOrNull(railIndex)
        recentsSelected = category == null
        selectedCategoryId = category?.id
        cells =
            if (category == null) {
                recents.map { SymbolCell(text = it, label = it) }
            } else {
                catalog.itemsIn(category.id).map { item -> SymbolCell(text = item.text, label = item.name) }
            }
        if (!keepScroll) resetScroll()
        invalidate()
    }

    override fun onRailSelected(index: Int) {
        reloadCells(index)
    }

    override fun cellCount(): Int = cells.size

    override fun cellHeightDp(layout: QuickContentPanelLayout): Double =
        PanelGeometry.wholeRowHeight(contentHeightDp(), layout.symbolCellMinHeight)

    override fun contentColumns(layout: QuickContentPanelLayout): Int {
        // Whole phrases (kaomoji) need the wider equal columns the layout reserves for them.
        val wholePhrase = cells.any { it.text.codePointCount(0, it.text.length) > 2 }
        if (wholePhrase) return layout.kaomojiColumnCount
        return PanelGeometry.columnCount(contentWidthDp(), layout.symbolColumnMinWidth)
    }

    override fun cellAccessibilityLabel(index: Int): String = cells.getOrNull(index)?.label.orEmpty()

    override fun onCellActivated(index: Int) {
        val cell = cells.getOrNull(index) ?: return
        // A lone ellipsis is typed as the Chinese six-dot form, which is what the mark means in text.
        onInsert(if (cell.text == "…") "……" else cell.text)
    }

    override fun bottomKeys(): List<BottomKey> =
        listOf(
            BottomKey(
                keyNames.chinese().getString(R.string.ime_back_chinese),
                context.getString(R.string.ime_return_to_letters),
                BOTTOM_SIDE_WEIGHT,
            ) { onClose() },
            BottomKey(
                keyNames.chinese().getString(R.string.ime_emoji_search),
                context.getString(R.string.ime_symbol_search_empty),
                BOTTOM_SEARCH_WEIGHT,
            ) { onSearch() },
            BottomKey(
                keyNames.chinese().getString(R.string.ime_space),
                context.getString(R.string.ime_space),
                BOTTOM_SPACE_WEIGHT,
            ) { onSpace() },
            BottomKey(
                context.getString(R.string.ime_glyph_delete),
                context.getString(R.string.ime_delete),
                BOTTOM_SIDE_WEIGHT,
            ) { onBackspace() },
        )

    override fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    ) {
        drawSymbolCap(canvas, cell, cells.getOrNull(index)?.text ?: return)
    }

    override fun emptyMessage(): String =
        if (recentsSelected && catalog.orderedCategories.isNotEmpty()) {
            keyNames.chinese().getString(R.string.ime_panel_recent_empty)
        } else {
            keyNames.chinese().getString(R.string.ime_panel_symbols_empty)
        }

    /** An empty recent list needs no way out; only an empty library points at the settings. */
    override fun emptyActionLabel(): String? =
        if (recentsSelected && catalog.orderedCategories.isNotEmpty()) {
            null
        } else {
            keyNames.chinese().getString(R.string.ime_panel_open_settings)
        }

    override fun onEmptyAction() {
        onOpenSettings()
    }
}

/**
 * Which rail slot a symbol-panel refresh must reopen.
 *
 * The "recent" slot appears and disappears as the recent list fills or empties, so an index kept
 * across a refresh can name a different category. The open category is therefore remembered by id
 * and looked up again; only the recent slot, which has no id, is positional.
 */
internal object SymbolRailSelection {
    fun reopenIndex(
        categories: List<SymbolCategory?>,
        recentsSelected: Boolean,
        selectedCategoryId: String?,
    ): Int {
        if (recentsSelected) return 0
        val index = categories.indexOfFirst { it?.id == selectedCategoryId }
        return if (index >= 0) index else 0
    }
}
