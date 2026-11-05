package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.ime.R
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageTarget

private const val BOTTOM_SIDE_WEIGHT = 1.3
private const val BOTTOM_SPACE_WEIGHT = 3.0
private const val BOTTOM_ENTER_WEIGHT = 1.6

/**
 * The categorized symbol page: a rail of category tabs and a scrolling grid of the open category's
 * keys, with the way back, the number page, space, the page lock, Delete and Return along the bottom.
 * The nine-key keyboard opens it for its symbol page, as does any pairing whose symbol page is
 * categorized.
 *
 * The categories are the page's own data ([KeyboardPageCategory]): the built-in page derives them
 * from the symbol library and the recent list, an authored page carries them. A key types its
 * literal (the Chinese or the ASCII branch of the page's marks) through [onInsert]; any other key a
 * category carries runs as a keyboard action through [onAction]. Like the symbol library panel, the
 * view never reads a file; the owner pushes the categories in.
 */
class SymbolPagePanelView(
    context: Context,
) : QuickContentPanelView(context) {
    /** Type one symbol; the owner records it and decides whether the page closes. */
    var onInsert: (String) -> Unit = {}

    /** A keyboard action: a category's function key or one of the bottom keys. */
    var onAction: (LayoutAction) -> Unit = {}

    /** Whether the page lock is on; read when the bottom bar is drawn and spoken. */
    var isLocked: () -> Boolean = { false }

    /** The editor's Return label; the key does what the editor's action says. */
    var enterLabel: String = ""

    private var categories: List<KeyboardPageCategory> = emptyList()
    private var asciiMarks = false

    /** The open category, remembered by id: a refreshed list can drop or add tabs around it. */
    private var openId: String? = null

    override val titleText: String get() = keyNames.chinese().getString(R.string.ime_panel_symbols_title)

    /**
     * Pushes the categories to show. The page opens on the first one; a refresh while it is open
     * keeps the tab and the scroll position.
     */
    fun update(
        categories: List<KeyboardPageCategory>,
        asciiMarks: Boolean,
        reopen: Boolean,
    ) {
        val browsing = captureBrowsingState()
        this.categories = categories
        this.asciiMarks = asciiMarks
        val index = if (reopen) 0 else categories.indexOfFirst { it.id == openId }.coerceAtLeast(0)
        openId = categories.getOrNull(index)?.id
        updateRail(
            categories.map { it.name },
            PanelBrowsingState(
                rail = index,
                railScrollDp = if (reopen) 0.0 else browsing.railScrollDp,
                contentScrollDp = if (reopen) 0.0 else browsing.contentScrollDp,
            ),
        )
        invalidate()
    }

    /**
     * A hidden page holds no categories: the next [update] fills the tabs and cells again. The open
     * category id stays so a refresh that reopens the page still finds its tab.
     */
    override fun releaseData() {
        super.releaseData()
        categories = emptyList()
        asciiMarks = false
        updateRail(emptyList())
        invalidate()
    }

    private fun open(): KeyboardPageCategory? = categories.getOrNull(selectedRail)

    private fun keys(): List<LayoutKey> = open()?.keys.orEmpty()

    override fun onRailSelected(index: Int) {
        openId = categories.getOrNull(index)?.id
        resetScroll()
    }

    override fun cellCount(): Int = keys().size

    override fun cellHeightDp(layout: QuickContentPanelLayout): Double =
        PanelGeometry.wholeRowHeight(contentHeightDp(), layout.symbolCellMinHeight)

    override fun contentColumns(layout: QuickContentPanelLayout): Int =
        PanelGeometry.symbolPageColumns(open()?.columns ?: 1, contentWidthDp(), railWidthDp(), MIN_COLUMN_DP)

    override fun cellAccessibilityLabel(index: Int): String = keys().getOrNull(index)?.face(asciiMarks).orEmpty()

    override fun onCellActivated(index: Int) {
        val key = keys().getOrNull(index) ?: return
        val action = key.action
        if (action.type == LayoutActionKind.LITERAL) {
            val text = action.literalValue(asciiMarks) ?: return
            // A lone ellipsis is typed as the Chinese six-dot form, which is what the mark means in text.
            onInsert(if (text == "…") "……" else text)
        } else {
            onAction(action)
        }
    }

    override fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    ) {
        drawSymbolCap(canvas, cell, keys().getOrNull(index)?.face(asciiMarks) ?: return)
    }

    override fun bottomKeys(): List<BottomKey> =
        listOf(
            BottomKey(
                context.getString(R.string.ime_back_chinese),
                context.getString(R.string.ime_return_to_letters),
                BOTTOM_SIDE_WEIGHT,
            ) { onAction(LayoutAction.pageSwitch(LayoutPageTarget.LETTERS)) },
            BottomKey(
                keyNames.chinese().getString(R.string.ime_numbers),
                context.getString(R.string.ime_open_numbers_page),
                BOTTOM_SIDE_WEIGHT,
            ) { onAction(LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC)) },
            BottomKey(
                keyNames.chinese().getString(R.string.ime_space),
                context.getString(R.string.ime_space),
                BOTTOM_SPACE_WEIGHT,
            ) { onAction(LayoutAction(LayoutActionKind.SPACE)) },
            BottomKey(
                keyNames.chinese().getString(if (isLocked()) R.string.ime_symbol_locked else R.string.ime_symbol_lock),
                context.getString(if (isLocked()) R.string.ime_symbol_locked else R.string.ime_symbol_lock),
                BOTTOM_SIDE_WEIGHT,
            ) {
                onAction(LayoutAction(LayoutActionKind.SYMBOL_LOCK))
                invalidate()
            },
            BottomKey(
                keyNames.chinese().getString(R.string.ime_glyph_delete),
                context.getString(R.string.ime_delete),
                BOTTOM_SIDE_WEIGHT,
            ) { onAction(LayoutAction(LayoutActionKind.BACKSPACE)) },
            BottomKey(enterLabel, enterLabel, BOTTOM_ENTER_WEIGHT) { onAction(LayoutAction(LayoutActionKind.ENTER)) },
        )

    override fun emptyMessage(): String? =
        if (open()?.source == KeyboardPageCategory.Source.RECENT) {
            keyNames.chinese().getString(R.string.ime_panel_recent_empty)
        } else {
            keyNames.chinese().getString(R.string.ime_panel_symbols_empty)
        }

    private companion object {
        /** A narrow window never squeezes a column below a comfortable tap target. */
        const val MIN_COLUMN_DP = 44.0
    }
}
