package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.accessibility.AccessibilityNodeInfo
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.content.QuickInsertDateFormat
import app.songyan.ime.ime.R
import app.songyan.ime.keyboard.support.SettingsTarget

/** Gap between a cell's card and its grid box. */
private const val CELL_INSET_DP = 2f

/** Clipboard rows sit a little looser than square cells. */
private const val ROW_INSET_DP = 3f

private const val CELL_TEXT_PADDING_DP = 10f

/** Shortest a clipboard row may get; taller rows are fine, a sliced one is not. */
private const val CLIPBOARD_ROW_MIN_DP = 48.0
private const val PHRASE_TEXT_SP = 13f
private const val DELETE_ICON_DP = 20f
private const val BOTTOM_SIDE_WEIGHT = 1.3
private const val BOTTOM_SEARCH_WEIGHT = 4.0

/**
 * Phrases and clipboard history in one panel: a rail with the two sections and, beside it, the
 * whole-phrase cells or the clipboard rows.
 *
 * Tapping a phrase or a clipboard entry inserts it ([onInsertText]); a clipboard row has a delete
 * zone on its right edge that removes the entry instead ([onDeleteClipboard]). Accessibility exposes
 * deletion as a named action; ordinary activation inserts the entry.
 */
class PhraseClipboardPanelView(
    context: Context,
) : QuickContentPanelView(context) {
    var onInsertText: (String) -> Unit = {}

    /** The empty state's action: the owner closes the panel and opens the settings screen. */
    var onOpenSettings: (SettingsTarget) -> Unit = {}
    var onDeleteClipboard: (String) -> Unit = {}
    var onEnableClipboard: () -> Unit = {}
    var onRepeat: () -> Unit = {}

    /** The phrases section's search key: the owner hands the keyboard over to type the query. */
    var onSearch: () -> Unit = {}
    var onInsertDateFormat: (QuickInsertDateFormat) -> Unit = {}

    private enum class Section {
        PHRASES,
        CLIPBOARD,
        QUICK,
    }

    private val sections = listOf(Section.PHRASES, Section.CLIPBOARD, Section.QUICK)

    /** One row of the quick-insert section: the text it shows and the insert it runs; null action is muted. */
    private class QuickRow(
        val label: String,
        val value: String?,
        val highlight: Boolean,
        val action: (() -> Unit)?,
    )

    private var phrases: List<String> = emptyList()
    private var clipboard: List<String> = emptyList()
    private var clipboardEnabled: Boolean = false
    private var quickRows: List<QuickRow> = emptyList()

    override val titleText: String get() = context.getString(R.string.ime_panel_phrases_title)

    /** Pushes both lists; the rail is fixed, so the panel always states where the history stands. */
    fun update(
        phrases: List<String>,
        clipboard: List<String>,
        clipboardEnabled: Boolean,
    ) {
        this.phrases = phrases
        this.clipboard = clipboard
        this.clipboardEnabled = clipboardEnabled
        updateRail(
            listOf(
                context.getString(R.string.ime_panel_phrases),
                context.getString(R.string.ime_panel_clipboard),
                context.getString(R.string.ime_panel_quick_insert),
            ),
        )
        applyWantedSection()
        invalidate()
    }

    /** Pushes the repeat and date/time rows; the date previews are as old as the panel opening. */
    fun updateQuickInsert(state: QuickInsertState) {
        val rows = ArrayList<QuickRow>()
        rows +=
            QuickRow(
                label = context.getString(R.string.ime_panel_repeat),
                value = state.repeatPreview ?: context.getString(R.string.ime_panel_nothing_to_repeat),
                highlight = false,
                action = if (state.repeatPreview != null) ({ onRepeat() }) else null,
            )
        // A row shows the text it inserts, so nobody has to guess what a format name means.
        for (dateRow in state.dateRows) {
            rows +=
                QuickRow(
                    label = dateRow.preview,
                    value = null,
                    highlight = dateRow.selected,
                    action = { onInsertDateFormat(dateRow.format) },
                )
        }
        quickRows = rows
        invalidate()
    }

    /** The section the next opening shows; applied as soon as the rail exists. */
    private var wantedSection: Section? = null

    /** Opens the panel on its clipboard history ([clipboard]) or on the phrases. */
    fun openSection(clipboard: Boolean) {
        wantedSection = if (clipboard) Section.CLIPBOARD else Section.PHRASES
        applyWantedSection()
    }

    /** Opens the panel on the quick-insert section. */
    fun openQuickInsert() {
        wantedSection = Section.QUICK
        applyWantedSection()
    }

    private fun applyWantedSection() {
        val wanted = wantedSection ?: return
        val index = sections.indexOf(wanted)
        if (railLabels.isEmpty()) return
        wantedSection = null
        selectRail(index)
    }

    private fun section(): Section = sections.getOrElse(selectedRail) { Section.PHRASES }

    private fun cells(): List<String> =
        when (section()) {
            Section.PHRASES -> phrases
            Section.CLIPBOARD -> clipboard
            Section.QUICK -> emptyList()
        }

    /** Only the phrases have a search term to look for; the other sections keep the whole height. */
    override fun bottomKeys(): List<BottomKey> =
        if (section() == Section.PHRASES) {
            listOf(
                BottomKey(
                    keyNames.chinese().getString(R.string.ime_back_chinese),
                    context.getString(R.string.ime_return_to_letters),
                    BOTTOM_SIDE_WEIGHT,
                ) { onClose() },
                BottomKey(
                    keyNames.chinese().getString(R.string.ime_emoji_search),
                    context.getString(R.string.ime_phrase_search_empty),
                    BOTTOM_SEARCH_WEIGHT,
                ) { onSearch() },
            )
        } else {
            emptyList()
        }

    override fun cellCount(): Int = if (section() == Section.QUICK) quickRows.size else cells().size

    override fun contentColumns(layout: QuickContentPanelLayout): Int =
        when (section()) {
            // Whole phrases read as blocks, the way the nine-key symbol page shows kaomoji.
            Section.PHRASES -> layout.kaomojiColumnCount
            Section.CLIPBOARD, Section.QUICK -> 1
        }

    override fun cellHeightDp(layout: QuickContentPanelLayout): Double {
        val minHeight =
            when (section()) {
                Section.PHRASES -> layout.symbolCellMinHeight
                // Rows keep a comfortable touch target even when the panel is short.
                Section.CLIPBOARD, Section.QUICK -> CLIPBOARD_ROW_MIN_DP
            }
        return PanelGeometry.wholeRowHeight(contentHeightDp(), minHeight)
    }

    override fun cellAccessibilityLabel(index: Int): String {
        if (section() == Section.QUICK) {
            val row = quickRows.getOrNull(index) ?: return ""
            return listOfNotNull(row.label, row.value).joinToString(", ")
        }
        return cells().getOrNull(index).orEmpty()
    }

    override fun cellCustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> =
        if (section() == Section.CLIPBOARD && index in clipboard.indices) {
            listOf(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.ime_clipboard_delete_action,
                    context.getString(R.string.ime_delete),
                ),
            )
        } else {
            emptyList()
        }

    override fun performCellCustomAction(
        index: Int,
        actionId: Int,
    ): Boolean {
        if (section() != Section.CLIPBOARD || actionId != R.id.ime_clipboard_delete_action) return false
        val text = clipboard.getOrNull(index) ?: return false
        onDeleteClipboard(text)
        return true
    }

    override fun onCellActivated(index: Int) {
        if (section() == Section.QUICK) {
            quickRows.getOrNull(index)?.action?.invoke()
            return
        }
        val text = cells().getOrNull(index) ?: return
        if (section() == Section.CLIPBOARD) {
            val cell = cellBoxAt(index) ?: return
            if (activationX() > px(cell.right) - deleteZoneDp() * density) {
                onDeleteClipboard(text)
                return
            }
        }
        onInsertText(text)
    }

    private fun deleteZoneDp(): Float = 36f

    override fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    ) {
        canvas.save()
        canvas.clipRect(px(cell.left), px(cell.top), px(cell.right), px(cell.bottom))
        when (section()) {
            Section.PHRASES -> cells().getOrNull(index)?.let { drawPhraseCell(canvas, cell, it) }
            Section.CLIPBOARD -> cells().getOrNull(index)?.let { drawClipboardRow(canvas, cell, it) }
            Section.QUICK -> quickRows.getOrNull(index)?.let { drawQuickRow(canvas, cell, it) }
        }
        canvas.restore()
    }

    /** A card like a clipboard row; an unavailable action is drawn muted and does nothing. */
    private fun drawQuickRow(
        canvas: Canvas,
        cell: PanelBox,
        row: QuickRow,
    ) {
        val radius = metrics.keyRadiusDp * density
        paint.color = if (row.highlight) palette.candidateSelectedBackground else palette.key
        rect.set(
            px(cell.left) + CELL_INSET_DP * density,
            px(cell.top) + ROW_INSET_DP * density,
            px(cell.right) - CELL_INSET_DP * density,
            px(cell.bottom) - ROW_INSET_DP * density,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        val enabled = row.action != null
        val baseline = px(cell.centerY) - (paint.ascent() + paint.descent()) / 2
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 14f * textScale
        paint.color =
            when {
                row.highlight -> palette.candidateSelectedText
                enabled -> palette.text
                else -> palette.muted
            }
        val left = rect.left + 16f * density
        val valuePx = if (row.value != null) (rect.width() - 32f * density) * 0.55f else 0f
        val labelRoom = rect.right - 16f * density - left - valuePx - (if (row.value != null) 8f * density else 0f)
        canvas.drawText(
            PanelText.ellipsize(row.label, labelRoom.coerceAtLeast(1f)) { paint.measureText(it) },
            left,
            baseline,
            paint,
        )
        val value = row.value ?: return
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = 13f * textScale
        paint.color = palette.muted
        canvas.drawText(
            PanelText.ellipsize(value, valuePx.coerceAtLeast(1f)) { paint.measureText(it) },
            rect.right - 16f * density,
            baseline,
            paint,
        )
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawPhraseCell(
        canvas: Canvas,
        cell: PanelBox,
        text: String,
    ) {
        val radius = metrics.keyRadiusDp * density
        paint.color = palette.key
        rect.set(
            px(cell.left) + CELL_INSET_DP * density,
            px(cell.top) + CELL_INSET_DP * density,
            px(cell.right) - CELL_INSET_DP * density,
            px(cell.bottom) - CELL_INSET_DP * density,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = palette.text
        paint.textAlign = Paint.Align.CENTER
        val room = rect.width() - CELL_TEXT_PADDING_DP * density
        paint.textSize =
            PanelText.fitSize(PHRASE_TEXT_SP * textScale, PanelText.MIN_FIT_SIZE_SP * textScale, room) { size ->
                paint.textSize = size
                paint.measureText(text)
            }
        val fitted =
            PanelText.ellipsize(text, room.coerceAtLeast(1f)) { measured ->
                paint.measureText(measured)
            }
        canvas.drawText(
            fitted,
            px(cell.centerX),
            px(cell.centerY) - (paint.ascent() + paint.descent()) / 2,
            paint,
        )
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawClipboardRow(
        canvas: Canvas,
        cell: PanelBox,
        text: String,
    ) {
        // A row is a card like a symbol cell, so the history reads as a list of entries instead of
        // loose text lines with a delete glyph floating at their end.
        val radius = metrics.keyRadiusDp * density
        paint.color = palette.key
        rect.set(
            px(cell.left) + CELL_INSET_DP * density,
            px(cell.top) + ROW_INSET_DP * density,
            px(cell.right) - CELL_INSET_DP * density,
            px(cell.bottom) - ROW_INSET_DP * density,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 14f * textScale
        val textLeft = rect.left + 16f * density
        val availablePx = rect.right - deleteZoneDp() * density - textLeft
        val shown =
            PanelText.ellipsize(text, availablePx.coerceAtLeast(1f)) { measured ->
                paint.measureText(measured)
            }
        canvas.drawText(
            shown,
            textLeft,
            px(cell.centerY) - (paint.ascent() + paint.descent()) / 2,
            paint,
        )
        closeIcon.draw(
            canvas,
            rect.right - deleteZoneDp() * density / 2 - 4f * density,
            px(cell.centerY),
            DELETE_ICON_DP * density,
            palette.muted,
        )
    }

    override fun emptyMessage(): String =
        when (section()) {
            Section.QUICK -> ""
            Section.PHRASES -> context.getString(R.string.ime_panel_phrases_empty)
            Section.CLIPBOARD ->
                if (clipboardEnabled) {
                    context.getString(R.string.ime_panel_clipboard_empty)
                } else {
                    context.getString(R.string.ime_panel_clipboard_disabled)
                }
        }

    override fun emptyActionLabel(): String =
        when (section()) {
            Section.QUICK -> ""
            Section.PHRASES -> context.getString(R.string.ime_panel_add_phrase)
            Section.CLIPBOARD ->
                context.getString(
                    if (clipboardEnabled) R.string.ime_panel_open_settings else R.string.ime_panel_clipboard_enable,
                )
        }

    override fun onEmptyAction() {
        when {
            section() == Section.PHRASES -> onOpenSettings(SettingsTarget.ADD_PHRASE)
            // Turned on in place, as Gboard does: a trip to settings would leave the app being typed in.
            section() == Section.CLIPBOARD && !clipboardEnabled -> onEnableClipboard()
            else -> onOpenSettings(SettingsTarget.QUICK_CONTENT)
        }
    }
}
