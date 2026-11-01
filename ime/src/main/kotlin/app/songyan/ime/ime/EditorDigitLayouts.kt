package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.PhoneKeypadMetrics

/**
 * The dial pad of a number, phone or date editor.
 *
 * A four-row, four-column grid like a phone's dialer: the digits 1-9 in three rows, the 0 below the 8,
 * Delete at the head of the right column, the action key (Return / Done / Next ...) spanning the
 * bottom two rows of that column, and, between them, the one extra character the editor most likely
 * needs (`.`, `-`, `+`, `/`, `:`). There is no space bar: none of these fields takes a space, except the phone pad's page of dial characters. Only
 * characters the editor accepts are offered, so a tap types a digit instead of hunting for it on the
 * ten-column symbol page, and a field that cannot take a sign never shows one.
 *
 * Number and verification-code fields are one kind ([NumericEditorKind.NUMBER]) and therefore share
 * one bottom row. The pad has no candidate bar and no toolbar above it: nothing is composed here.
 *
 * Pure data and geometry, so the key sets are unit-tested; the labels of Return and Delete come from
 * the keyboard view, which already localizes those faces.
 */
object EditorDigitLayouts {
    /** Rows and columns of every pad. */
    const val ROWS: Int = 4
    const val COLUMNS: Int = 4

    /** One key and where it sits in the grid. */
    data class Cell(
        val key: LayoutKey,
        val row: Int,
        val column: Int,
        val rowSpan: Int = 1,
        val columnSpan: Int = 1,
    )

    /** The keys of one pad. */
    data class Pad(
        val cells: List<Cell>,
    )

    private val BACKSPACE: LayoutKey = LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE))
    private val ENTER: LayoutKey = LayoutKey("", action = LayoutAction(LayoutActionKind.ENTER))
    private val SPACE: LayoutKey = LayoutKey("", action = LayoutAction(LayoutActionKind.SPACE))

    // The face of both is drawn from the page they open ("符号" / "123"), as on the other pages.
    private val TO_SYMBOLS: LayoutKey =
        LayoutKey("", action = LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))
    private val TO_DIGITS: LayoutKey =
        LayoutKey("", action = LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC))

    /** The pad for [kind], or null when the editor keeps the normal keyboard pages. */
    fun pad(
        kind: NumericEditorKind,
        flags: NumberInputFlags = NumberInputFlags(),
        dateTime: DateTimeFlags = DateTimeFlags(),
        phoneSymbols: Boolean = false,
    ): Pad? {
        if (kind == NumericEditorKind.NONE) return null
        // The same instance for the same editor: the key view skips re-solving a pad it already shows.
        val symbols = phoneSymbols && kind == NumericEditorKind.PHONE
        return built.getOrPut(Key(kind, flags, dateTime, symbols)) {
            if (symbols) phoneSymbolsPad() else create(kind, flags, dateTime)
        }
    }

    private data class Key(
        val kind: NumericEditorKind,
        val flags: NumberInputFlags,
        val dateTime: DateTimeFlags,
        val phoneSymbols: Boolean,
    )

    private val built = HashMap<Key, Pad>()

    /**
     * The rest of a dialer's characters, the set AOSP's phone keyboard offers on its symbol page
     * (LatinIME rows_phone_symbols.xml): `( ) / - N`, `.`, `+`, a space, and the two dial controls
     * `,` (pause) and `;` (wait) that [android.text.InputType.TYPE_CLASS_PHONE] fields accept.
     */
    private fun phoneSymbolsPad(): Pad {
        val rows =
            listOf(
                listOf("(", "/", ")"),
                listOf("N", ",", ";"),
                listOf("*", ".", "#"),
            )
        val cells = mutableListOf<Cell>()
        rows.forEachIndexed { row, chars ->
            chars.forEachIndexed { column, char -> cells += Cell(literal(char), row, column) }
        }
        cells += Cell(BACKSPACE, row = 0, column = 3)
        cells += Cell(literal("-"), row = 1, column = 3)
        cells += Cell(TO_DIGITS, row = 2, column = 3)
        cells += Cell(literal("+"), row = 3, column = 0)
        cells += Cell(SPACE, row = 3, column = 1, columnSpan = 2)
        cells += Cell(ENTER, row = 3, column = 3)
        return Pad(cells)
    }

    /** The extra characters of an editor: beside the digits (right column), and left / right of the 0. */
    private data class Extras(
        val side: String?,
        val left: String?,
        val right: String?,
    )

    private fun extras(
        kind: NumericEditorKind,
        flags: NumberInputFlags,
        dateTime: DateTimeFlags,
    ): Extras =
        when (kind) {
            NumericEditorKind.PHONE -> Extras(side = "+", left = "*", right = "#")
            // A fraction takes the point beside the digits; the sign then moves next to the 0.
            NumericEditorKind.NUMBER ->
                Extras(
                    side =
                        if (flags.decimal) {
                            "."
                        } else if (flags.signed) {
                            "-"
                        } else {
                            null
                        },
                    left = if (flags.decimal && flags.signed) "-" else null,
                    right = null,
                )
            NumericEditorKind.DATETIME -> {
                // A date field takes `/`, `.` and `-`, a time field only `:`, a field with no
                // variation one separator of each kind.
                val separators =
                    when {
                        dateTime.time && !dateTime.date -> listOf(":")
                        dateTime.date && !dateTime.time -> listOf("/", "-", ".")
                        else -> listOf("/", ":", "-")
                    }
                Extras(separators.getOrNull(0), separators.getOrNull(1), separators.getOrNull(2))
            }
            NumericEditorKind.NONE -> Extras(null, null, null)
        }

    private fun create(
        kind: NumericEditorKind,
        flags: NumberInputFlags,
        dateTime: DateTimeFlags,
    ): Pad {
        val extras = extras(kind, flags, dateTime)
        val cells = mutableListOf<Cell>()
        for (digit in 1..9) {
            cells += Cell(literal(digit.toString()), row = (digit - 1) / 3, column = (digit - 1) % 3)
        }
        cells += Cell(literal("0"), row = 3, column = 1)
        extras.left?.let { cells += Cell(literal(it), row = 3, column = 0) }
        extras.right?.let { cells += Cell(literal(it), row = 3, column = 2) }
        if (extras.side != null) {
            cells += Cell(BACKSPACE, row = 0, column = 3)
            cells += Cell(literal(extras.side), row = 1, column = 3)
        } else {
            // Nothing to put between them: Delete takes the two rows.
            cells += Cell(BACKSPACE, row = 0, column = 3, rowSpan = 2)
        }
        if (kind == NumericEditorKind.PHONE) {
            // The page of the other dial characters takes the cell the action key would span.
            cells += Cell(TO_SYMBOLS, row = 2, column = 3)
            cells += Cell(ENTER, row = 3, column = 3)
        } else {
            cells += Cell(ENTER, row = 2, column = 3, rowSpan = 2)
        }
        return Pad(cells)
    }

    /**
     * The pad's key rectangles in dp inside a [widthDp] x [heightDp] key area: four equal bands, four
     * equal columns, the spanning keys covering their rows. The nine-key shell's own geometry type is
     * reused so the view draws and hit-tests both the same way.
     */
    fun build(
        pad: Pad,
        widthDp: Float,
        heightDp: Float,
    ): PhoneKeypadLayout.Shell {
        val metrics = PhoneKeypadMetrics.resolve(widthDp.toDouble(), heightDp / ROWS.toDouble())
        val gap = metrics.spacing.toFloat()
        val column = (widthDp - (COLUMNS - 1) * gap) / COLUMNS
        val pitch = heightDp / ROWS
        val keys =
            pad.cells.map { cell ->
                val left = cell.column * (column + gap)
                PhoneKeypadLayout.PlacedKey(
                    region = PhoneKeypadLayout.Region.MAIN,
                    key = cell.key,
                    left = left,
                    top = cell.row * pitch,
                    right = left + cell.columnSpan * column + (cell.columnSpan - 1) * gap,
                    bottom = (cell.row + cell.rowSpan) * pitch,
                    rowSpan = cell.rowSpan,
                )
            }
        return PhoneKeypadLayout.Shell(metrics, keys, heightDp)
    }

    private fun literal(value: String): LayoutKey = LayoutKey(label = value, action = LayoutAction.literal(value))
}
