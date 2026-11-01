package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.songyan.ime.content.EmojiCategory
import app.songyan.ime.content.EmojiEntry
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.ime.R

private const val EMOJI_TEXT_SP = 26f
private const val BOTTOM_SIDE_WEIGHT = 1.3
private const val BOTTOM_SEARCH_WEIGHT = 4.0
private const val EMOJI_COLUMN_MIN_DP = 44.0
private const val ACTION_CHOOSE_SKIN_TONE = 0x53594432

/**
 * The emoji panel: a rail of categories (with a leading "recent" choice) and a grid of emoji.
 *
 * Holding an emoji that has skin tones swaps the grid for its variants; picking one inserts it (and
 * the recent list then remembers that exact variant), tapping a rail choice or the title's close
 * button leaves the variants. Searching is not done here: the search key hands over to the keyboard,
 * which has the letters.
 *
 * The catalog arrives through [update], already limited to emoji this device (and the editor) can
 * draw; the view never reads files or the font.
 */
class EmojiPanelView(
    context: Context,
) : QuickContentPanelView(context) {
    var onInsert: (String) -> Unit = {}
    var onSearch: () -> Unit = {}
    var onBackspace: () -> Unit = {}

    private var categories: List<EmojiCategory> = emptyList()
    private var recents: List<String> = emptyList()
    private var loaded = false

    /** One entry per rail slot; null is the "recent" choice, always the first. */
    private var railCategories: List<EmojiCategory?> = emptyList()
    private var cells: List<String> = emptyList()
    private var cellEntries: List<EmojiEntry?> = emptyList()

    /** The emoji whose skin tones are showing instead of the category. */
    private var variantsOf: EmojiEntry? = null
    private var recentsSelected = true

    /** The panel was just opened: the first catalog push picks the page to show. */
    private var opening = true
    private var selectedCategoryId: String? = null

    override val titleText: String
        get() =
            context.getString(
                if (variantsOf != null) R.string.ime_emoji_skin_tone_title else R.string.ime_panel_emoji_title,
            )

    /** Pushes the catalog and the recent list; the open category and scroll offsets are kept. */
    fun update(
        categories: List<EmojiCategory>,
        recents: List<String>,
    ) {
        val browsing = captureBrowsingState()
        this.categories = categories
        this.recents = recents
        loaded = true
        val labels = ArrayList<String>()
        val slots = ArrayList<EmojiCategory?>()
        labels += context.getString(R.string.ime_panel_recent)
        slots += null
        for (category in categories) {
            labels += categoryName(category.id)
            slots += category
        }
        railCategories = slots
        val wanted =
            if (opening) {
                // A first open lands on the recent emoji, or on the first category when there are none.
                if (recents.isEmpty() && slots.size > 1) 1 else 0
            } else if (recentsSelected) {
                0
            } else {
                railCategories.indexOfFirst { it?.id == selectedCategoryId }.takeIf { it >= 0 } ?: 0
            }
        opening = false
        updateRail(
            labels,
            PanelBrowsingState(wanted, browsing.railScrollDp, browsing.contentScrollDp),
        )
        loadCells(wanted, keepScroll = true)
    }

    /** A hidden panel keeps no catalog: opening it loads the catalog and the recents again. */
    override fun releaseData() {
        super.releaseData()
        categories = emptyList()
        recents = emptyList()
        loaded = false
        railCategories = emptyList()
        cells = emptyList()
        cellEntries = emptyList()
        variantsOf = null
        entryLookup = { null }
        updateRail(emptyList())
        opening = true
    }

    /** The recent list changed after an insert; nothing else moves. */
    fun updateRecents(recents: List<String>) {
        this.recents = recents
        if (recentsSelected && variantsOf == null) loadCells(0, keepScroll = true)
    }

    /** Back to the category grid; a closed panel never reopens on a variants page. */
    fun reset() {
        opening = true
        if (variantsOf == null) return
        variantsOf = null
        loadCells(selectedRail, keepScroll = false)
    }

    private fun categoryName(id: String): String =
        context.getString(
            when (id) {
                "Smileys & Emotion" -> R.string.ime_emoji_category_smileys
                "People & Body" -> R.string.ime_emoji_category_people
                "Animals & Nature" -> R.string.ime_emoji_category_animals
                "Food & Drink" -> R.string.ime_emoji_category_food
                "Travel & Places" -> R.string.ime_emoji_category_travel
                "Activities" -> R.string.ime_emoji_category_activities
                "Objects" -> R.string.ime_emoji_category_objects
                "Symbols" -> R.string.ime_emoji_category_symbols
                else -> R.string.ime_emoji_category_flags
            },
        )

    private fun loadCells(
        railIndex: Int,
        keepScroll: Boolean,
    ) {
        val variants = variantsOf
        if (variants != null) {
            cells = listOf(variants.emoji) + variants.variants
            cellEntries = cells.map { null }
        } else {
            val category = railCategories.getOrNull(railIndex)
            recentsSelected = category == null
            selectedCategoryId = category?.id
            if (category == null) {
                cells = recents
                cellEntries = recents.map { null }
            } else {
                cells = category.emojis.map { it.emoji }
                cellEntries = category.emojis
            }
        }
        if (!keepScroll) resetScroll()
        invalidate()
    }

    override fun onRailSelected(index: Int) {
        opening = false
        variantsOf = null
        loadCells(index, keepScroll = false)
    }

    override fun cellCount(): Int = cells.size

    override fun contentColumns(layout: QuickContentPanelLayout): Int =
        PanelGeometry.columnCount(contentWidthDp(), EMOJI_COLUMN_MIN_DP)

    override fun cellHeightDp(layout: QuickContentPanelLayout): Double =
        PanelGeometry.wholeRowHeight(contentHeightDp(), layout.symbolCellMinHeight)

    override fun cellAccessibilityLabel(index: Int): String = cells.getOrNull(index).orEmpty()

    override fun onCellActivated(index: Int) {
        val text = cells.getOrNull(index) ?: return
        onInsert(text)
        // Variants are a one-shot choice: after one is inserted the category is back.
        if (variantsOf != null) reset()
    }

    override fun onCellLongPressed(index: Int): Boolean = openVariants(index)

    /** The skin-tone picker is also a named action, because a screen reader user cannot hold a cell. */
    override fun cellCustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> =
        if (skinToneEntry(index) != null) {
            listOf(
                AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_CHOOSE_SKIN_TONE,
                    context.getString(R.string.ime_emoji_choose_skin_tone_action),
                ),
            )
        } else {
            emptyList()
        }

    override fun performCellCustomAction(
        index: Int,
        actionId: Int,
    ): Boolean = actionId == ACTION_CHOOSE_SKIN_TONE && openVariants(index)

    private fun skinToneEntry(index: Int): EmojiEntry? {
        if (variantsOf != null) return null
        val entry = cellEntries.getOrNull(index) ?: recentEntry(index) ?: return null
        return entry.takeIf { it.variants.isNotEmpty() }
    }

    private fun openVariants(index: Int): Boolean {
        val entry = skinToneEntry(index) ?: return false
        variantsOf = entry
        loadCells(selectedRail, keepScroll = false)
        // The grid is now the picker: tell a screen reader, whose focus was on the cell that is gone.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        return true
    }

    /** The recent list stores inserted forms; the base entry of one of them offers the skin tones. */
    private var entryLookup: (String) -> EmojiEntry? = { null }

    fun lookup(find: (String) -> EmojiEntry?) {
        entryLookup = find
    }

    private fun recentEntry(index: Int): EmojiEntry? =
        if (recentsSelected && variantsOf == null) cells.getOrNull(index)?.let(entryLookup) else null

    override fun bottomKeys(): List<BottomKey> =
        listOf(
            BottomKey(
                context.getString(R.string.ime_back_chinese),
                context.getString(R.string.ime_return_to_letters),
                BOTTOM_SIDE_WEIGHT,
            ) { onClose() },
            BottomKey(
                keyNames.chinese().getString(R.string.ime_emoji_search),
                context.getString(R.string.ime_emoji_search),
                BOTTOM_SEARCH_WEIGHT,
            ) { onSearch() },
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
        val text = cells.getOrNull(index) ?: return
        paint.color = palette.text
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = EMOJI_TEXT_SP * textScale
        canvas.drawText(text, px(cell.centerX), px(cell.centerY) - (paint.ascent() + paint.descent()) / 2, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    override fun emptyMessage(): String? =
        when {
            !loaded -> context.getString(R.string.ime_emoji_loading)
            recentsSelected -> context.getString(R.string.ime_emoji_recent_empty)
            else -> null
        }
}
