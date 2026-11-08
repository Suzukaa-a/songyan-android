package app.songyan.ime.ime.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.ime.R
import app.songyan.ime.ime.panels.PanelBox
import app.songyan.ime.ime.panels.PanelText
import app.songyan.ime.ime.panels.QuickContentPanelView

/** One row of the tools panel, in drawing order. */
private sealed interface KeyboardToolsCell {
    /** The text the tool will process. */
    data class Source(
        val text: String,
    ) : KeyboardToolsCell

    /** One way to pick the source text; the active one is announced as selected. */
    data class Choice(
        val kind: KeyboardToolSourceKind,
        val enabled: Boolean,
    ) : KeyboardToolsCell

    /** One enabled tool. */
    data class Tool(
        val tool: KeyboardTool,
    ) : KeyboardToolsCell

    /** One other app the user turned on; picking it hands it the text. */
    data class App(
        val app: KeyboardToolApp,
    ) : KeyboardToolsCell

    /** One speech app the user turned on; it needs no source text. */
    data class Speech(
        val app: KeyboardToolApp,
    ) : KeyboardToolsCell

    /** What the tool, or the other app, produced. */
    data class Result(
        val text: String,
    ) : KeyboardToolsCell

    /** A line of copy: a state, a failure or a notice. */
    data class Message(
        val text: String,
    ) : KeyboardToolsCell

    /** The empty list's only action: open the settings where tools are managed. */
    data object Manage : KeyboardToolsCell
}

/** Everything the panel draws, pushed by the session. */
internal data class KeyboardToolsPanelState(
    val kind: KeyboardToolSourceKind = KeyboardToolSourceKind.FIELD,
    val selectionAvailable: Boolean = false,
    val fieldAvailable: Boolean = false,
    val clipboardAvailable: Boolean = false,
    val source: KeyboardToolText? = null,
    /** Null while the list is still loading. */
    val tools: List<KeyboardTool>? = null,
    val toolsUnavailable: Boolean = false,
    /** The other apps the user turned on; null while they load or when there is no relay. */
    val apps: List<KeyboardToolApp>? = null,
    val appsUnavailable: Boolean = false,
    /** The speech apps the user turned on; null while they load or when there is no relay. */
    val speechApps: List<KeyboardToolApp>? = null,
    val selectedTool: String? = null,
    val running: Boolean = false,
    val result: String? = null,
    val truncated: Boolean = false,
    val failure: KeyboardToolFailure? = null,
    val copied: Boolean = false,
    /** The answer another app returned, previewed until the user applies it. */
    val answer: KeyboardToolAnswer? = null,
    /** The app the panel just handed the text to. */
    val waitingFor: String? = null,
    /** The app that could not be opened; nothing was handed to it. */
    val appFailed: String? = null,
)

/**
 * The keyboard tool panel: the source text on top, the tools and the other apps below it, the result
 * and the three ways to use it at the bottom.
 *
 * It is a canvas panel like the other keyboard panels, not Compose: the IME window is a plain view
 * tree laid out by the keyboard, and a panel that answers a screen reader item by item is what the
 * other panels already do. The view only draws and reports taps; the session decides what a tap
 * means and what is written into the editor.
 */
internal class KeyboardToolsPanel(
    context: Context,
) : QuickContentPanelView(context) {
    /** A source choice was tapped: switch the text the tools work on. */
    var onChoice: (KeyboardToolSourceKind) -> Unit = {}

    /** A tool was tapped: run it on the current source. */
    var onTool: (String) -> Unit = {}

    /** One other app was tapped: hand it the current source. */
    var onApp: (String, String) -> Unit = { _, _ -> }

    /** One speech app was tapped: start it for the editor in front. */
    var onSpeech: (String, String) -> Unit = { _, _ -> }

    /** Write the result over the source text. */
    var onReplace: () -> Unit = {}

    /** Insert the result at the caret. */
    var onInsert: () -> Unit = {}

    /** Copy the result. */
    var onCopy: () -> Unit = {}

    /** Leave the panel for the settings where tools are managed. */
    var onManage: () -> Unit = {}

    private var state = KeyboardToolsPanelState()
    private var cells: List<KeyboardToolsCell> = emptyList()

    override val titleText: String get() = context.getString(R.string.ime_tools_title)

    /** Redraws with [value]; the cells are rebuilt so the grid and the screen reader agree. */
    internal fun update(value: KeyboardToolsPanelState) {
        val previous = state
        state = value
        cells = cellsOf(value)
        announceItemsChanged()
        // The result is the last row; bring it into view when a run or an answer just arrived,
        // instead of leaving the user at the top of a list they have to scroll through.
        val arrived =
            (value.result != null && value.result != previous.result) ||
                (value.answer != null && value.answer != previous.answer)
        if (arrived) scrollToContentEnd()
        invalidate()
    }

    override fun cellCount(): Int = cells.size

    override fun contentColumns(layout: QuickContentPanelLayout): Int = 1

    override fun cellHeightDp(layout: QuickContentPanelLayout): Double = CELL_HEIGHT_DP

    /**
     * The actions only exist once there is something to use: an empty bar before a run would offer
     * buttons that cannot do anything.
     */
    override fun bottomKeys(): List<BottomKey> {
        val answer = state.answer
        if (answer != null) {
            if (state.running) return emptyList()
            return buildList {
                if (answer.canReplace) {
                    val label = context.getString(R.string.ime_tools_replace)
                    add(BottomKey(label, label, 1.0) { onReplace() })
                }
                val insert = context.getString(R.string.ime_tools_insert)
                add(BottomKey(insert, insert, 1.0) { onInsert() })
                val copy = context.getString(R.string.ime_tools_copy)
                add(BottomKey(copy, copy, 1.0) { onCopy() })
            }
        }
        val result = state.result ?: return emptyList()
        if (state.running) return emptyList()
        val source = state.source
        val canReplace =
            source != null &&
                source.kind != KeyboardToolSourceKind.CLIPBOARD &&
                !state.truncated
        return buildList {
            if (canReplace) {
                val label = context.getString(R.string.ime_tools_replace)
                add(BottomKey(label, label, 1.0) { onReplace() })
            }
            val insert = context.getString(R.string.ime_tools_insert)
            add(BottomKey(insert, insert, 1.0) { onInsert() })
            val copy = context.getString(R.string.ime_tools_copy)
            add(BottomKey(copy, copy, 1.0) { onCopy() })
        }
    }

    override fun cellEnabled(index: Int): Boolean =
        when (val cell = cells.getOrNull(index)) {
            is KeyboardToolsCell.Choice -> cell.enabled
            is KeyboardToolsCell.Tool,
            is KeyboardToolsCell.App,
            is KeyboardToolsCell.Speech,
            is KeyboardToolsCell.Manage,
            -> true
            else -> false
        }

    override fun itemSelected(index: Int): Boolean = isSelectedCell(index - cellItemBase())

    private fun isSelectedCell(index: Int): Boolean =
        when (val cell = cells.getOrNull(index)) {
            is KeyboardToolsCell.Choice -> cell.kind == state.kind
            is KeyboardToolsCell.Tool -> cell.tool.id == state.selectedTool
            else -> false
        }

    override fun cellAccessibilityLabel(index: Int): String =
        when (val cell = cells.getOrNull(index)) {
            is KeyboardToolsCell.Source -> context.getString(R.string.ime_tools_source_label, cell.text)
            is KeyboardToolsCell.Choice -> context.getString(choiceLabel(cell.kind))
            is KeyboardToolsCell.Tool -> cell.tool.label
            is KeyboardToolsCell.App -> cell.app.label
            is KeyboardToolsCell.Speech -> cell.app.label
            is KeyboardToolsCell.Result -> context.getString(R.string.ime_tools_result_label, cell.text)
            is KeyboardToolsCell.Message -> cell.text
            is KeyboardToolsCell.Manage -> context.getString(R.string.ime_tools_manage)
            null -> ""
        }

    override fun onCellActivated(index: Int) {
        when (val cell = cells.getOrNull(index)) {
            is KeyboardToolsCell.Choice -> onChoice(cell.kind)
            is KeyboardToolsCell.Tool -> onTool(cell.tool.id)
            is KeyboardToolsCell.App -> onApp(cell.app.id, cell.app.label)
            is KeyboardToolsCell.Speech -> onSpeech(cell.app.id, cell.app.label)
            is KeyboardToolsCell.Manage -> onManage()
            else -> Unit
        }
    }

    override fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    ) {
        val entry = cells.getOrNull(index) ?: return
        val label = cellAccessibilityLabel(index)
        val selected = isSelectedCell(index)
        val rounded = metrics.keyRadiusDp * density
        val inset = CELL_INSET_DP * density
        val box =
            rect.apply {
                set(
                    px(cell.left) + inset,
                    px(cell.top) + inset,
                    px(cell.right) - inset,
                    px(cell.bottom) - inset,
                )
            }
        val drawn =
            when (entry) {
                is KeyboardToolsCell.Source, is KeyboardToolsCell.Result -> {
                    paint.color = palette.key
                    canvas.drawRoundRect(box, rounded, rounded, paint)
                    paint.color = palette.text
                    label
                }
                is KeyboardToolsCell.Choice,
                is KeyboardToolsCell.Tool,
                is KeyboardToolsCell.App,
                is KeyboardToolsCell.Speech,
                is KeyboardToolsCell.Manage,
                -> {
                    paint.color = if (selected) palette.candidateSelectedBackground else palette.control
                    canvas.drawRoundRect(box, rounded, rounded, paint)
                    paint.color = if (selected) palette.candidateSelectedText else palette.text
                    label
                }
                is KeyboardToolsCell.Message -> {
                    // Plain copy, not a button: no pill, so it never looks tappable.
                    paint.color = palette.muted
                    label
                }
            }
        paint.textAlign = Paint.Align.LEFT
        paint.textSize =
            PanelText.fitSize(LABEL_TEXT_SP * textScale, PanelText.MIN_FIT_SIZE_SP * textScale, box.width()) { size ->
                paint.textSize = size
                paint.measureText(drawn)
            }
        val room = box.width() - 2 * TEXT_PADDING_DP * density
        val fitted = PanelText.ellipsize(drawn, room.coerceAtLeast(1f)) { measured -> paint.measureText(measured) }
        canvas.drawText(
            fitted,
            box.left + TEXT_PADDING_DP * density,
            box.centerY() - (paint.ascent() + paint.descent()) / 2,
            paint,
        )
    }

    private fun cellsOf(value: KeyboardToolsPanelState): List<KeyboardToolsCell> =
        buildList {
            val source = value.source
            add(
                if (source != null) {
                    KeyboardToolsCell.Source(source.text)
                } else {
                    // An editor that offers no text still shows why, so the panel is never blank.
                    KeyboardToolsCell.Message(context.getString(R.string.ime_tools_source_none))
                },
            )
            val answer = value.answer
            if (answer != null) {
                // An answer in hand replaces the entry list: the user has one decision to make here.
                add(
                    KeyboardToolsCell.Message(
                        if (answer.appLabel.isEmpty()) {
                            context.getString(R.string.ime_process_text_answer)
                        } else {
                            context.getString(R.string.ime_process_text_answer_from, answer.appLabel)
                        },
                    ),
                )
                add(KeyboardToolsCell.Result(answer.text))
                if (value.copied) add(KeyboardToolsCell.Message(context.getString(R.string.ime_tools_copied)))
                return@buildList
            }
            add(KeyboardToolsCell.Choice(KeyboardToolSourceKind.SELECTION, value.selectionAvailable))
            add(KeyboardToolsCell.Choice(KeyboardToolSourceKind.FIELD, value.fieldAvailable))
            add(KeyboardToolsCell.Choice(KeyboardToolSourceKind.CLIPBOARD, value.clipboardAvailable))
            val tools = value.tools
            when {
                value.toolsUnavailable ->
                    add(KeyboardToolsCell.Message(context.getString(R.string.ime_tools_failure_store)))
                tools == null -> add(KeyboardToolsCell.Message(context.getString(R.string.ime_tools_loading)))
                tools.isEmpty() -> {
                    add(KeyboardToolsCell.Message(context.getString(R.string.ime_tools_none)))
                    add(KeyboardToolsCell.Manage)
                }
                else -> tools.forEach { add(KeyboardToolsCell.Tool(it)) }
            }
            val apps = value.apps
            when {
                value.appsUnavailable ->
                    add(KeyboardToolsCell.Message(context.getString(R.string.ime_process_text_unavailable)))

                apps != null && apps.isNotEmpty() -> {
                    add(KeyboardToolsCell.Message(context.getString(R.string.ime_process_text_apps_section)))
                    apps.forEach { add(KeyboardToolsCell.App(it)) }
                }
            }
            val speech = value.speechApps
            if (!speech.isNullOrEmpty()) {
                add(KeyboardToolsCell.Message(context.getString(R.string.ime_process_text_speech_section)))
                speech.forEach { add(KeyboardToolsCell.Speech(it)) }
            }
            value.waitingFor?.let {
                add(KeyboardToolsCell.Message(context.getString(R.string.ime_process_text_waiting, it)))
            }
            if (value.running) add(KeyboardToolsCell.Message(context.getString(R.string.ime_tools_running)))
            value.result?.let { add(KeyboardToolsCell.Result(it)) }
            if (value.truncated) {
                add(KeyboardToolsCell.Message(context.getString(R.string.ime_tools_result_truncated)))
            }
            if (value.copied) add(KeyboardToolsCell.Message(context.getString(R.string.ime_tools_copied)))
            value.failure?.let { add(KeyboardToolsCell.Message(failureLabel(it))) }
            value.appFailed?.let {
                add(KeyboardToolsCell.Message(context.getString(R.string.ime_process_text_open_failed, it)))
            }
        }

    private fun choiceLabel(kind: KeyboardToolSourceKind): Int =
        when (kind) {
            KeyboardToolSourceKind.SELECTION -> R.string.ime_tools_source_selection
            KeyboardToolSourceKind.FIELD -> R.string.ime_tools_source_field
            KeyboardToolSourceKind.CLIPBOARD -> R.string.ime_tools_source_clipboard
        }

    private fun failureLabel(failure: KeyboardToolFailure): String =
        context.getString(
            when (failure) {
                KeyboardToolFailure.UNKNOWN_TOOL -> R.string.ime_tools_failure_unknown_tool
                KeyboardToolFailure.INVALID_TOOL -> R.string.ime_tools_failure_invalid_tool
                KeyboardToolFailure.UNAVAILABLE -> R.string.ime_tools_failure_unavailable
                KeyboardToolFailure.TIMED_OUT -> R.string.ime_tools_failure_timed_out
                KeyboardToolFailure.TOO_LONG -> R.string.ime_tools_failure_too_long
                KeyboardToolFailure.FAILED -> R.string.ime_tools_failure_failed
                KeyboardToolFailure.STORE_UNAVAILABLE -> R.string.ime_tools_failure_store
            },
        )

    private companion object {
        /** One row: tall enough to read a short sentence and still fit four rows. */
        const val CELL_HEIGHT_DP = 44.0
        const val LABEL_TEXT_SP = 14f
        const val TEXT_PADDING_DP = 10f
        const val CELL_INSET_DP = 2f
    }
}
