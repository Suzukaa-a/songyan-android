package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDigitLayoutsTest {
    private val flagSets =
        listOf(
            NumberInputFlags(),
            NumberInputFlags(signed = true),
            NumberInputFlags(decimal = true),
            NumberInputFlags(signed = true, decimal = true),
        )

    private val dateTimeSets =
        listOf(
            DateTimeFlags(),
            DateTimeFlags(date = true),
            DateTimeFlags(time = true),
            DateTimeFlags(date = true, time = true),
        )

    private val kinds = listOf(NumericEditorKind.NUMBER, NumericEditorKind.PHONE, NumericEditorKind.DATETIME)

    private fun pad(
        kind: NumericEditorKind,
        flags: NumberInputFlags = NumberInputFlags(),
        dateTime: DateTimeFlags = DateTimeFlags(),
    ) = requireNotNull(EditorDigitLayouts.pad(kind, flags, dateTime))

    private fun EditorDigitLayouts.Pad.labels(): List<String> = cells.map { it.key.label }

    private fun EditorDigitLayouts.Pad.at(
        row: Int,
        column: Int,
    ) = cells.firstOrNull { cell -> cell.column == column && row in cell.row until cell.row + cell.rowSpan }

    private fun EditorDigitLayouts.Pad.every(): List<EditorDigitLayouts.Pad> = listOf(this)

    private fun allPads(): List<EditorDigitLayouts.Pad> =
        kinds.flatMap { kind ->
            flagSets.flatMap { flags -> dateTimeSets.map { dateTime -> pad(kind, flags, dateTime) } }
        }

    @Test
    fun noEditorKindKeepsTheNormalPages() {
        assertNull(EditorDigitLayouts.pad(NumericEditorKind.NONE))
    }

    @Test
    fun everyPadIsADialPadWithTheZeroBelowTheEight() {
        for (pad in allPads()) {
            assertEquals((1..9).map { it.toString() }, (0..8).map { pad.at(it / 3, it % 3)!!.key.label })
            assertEquals("0", pad.at(3, 1)!!.key.label)
            assertEquals(1, pad.cells.count { it.key.label == "0" })
        }
    }

    @Test
    fun deleteHeadsTheRightColumnAndTheActionKeyCoversTheLastTwoRows() {
        for (pad in allPads().filter { pad -> pad.cells.none { it.key.action.type == LayoutActionKind.PAGE_SWITCH } }) {
            assertEquals(
                LayoutActionKind.BACKSPACE,
                pad
                    .at(0, 3)!!
                    .key.action.type,
            )
            val action = pad.at(3, 3)!!
            assertEquals(LayoutActionKind.ENTER, action.key.action.type)
            assertEquals(2, action.rowSpan)
            assertEquals(2, action.row)
            assertSame(action, pad.at(2, 3))
        }
    }

    private fun assertSame(
        expected: EditorDigitLayouts.Cell,
        actual: EditorDigitLayouts.Cell?,
    ) = assertTrue(expected === actual)

    @Test
    fun noPadHasASpaceBarOrOverlappingKeys() {
        for (pad in allPads()) {
            assertFalse(pad.cells.any { it.key.action.type == LayoutActionKind.SPACE })
            val occupied = HashSet<Pair<Int, Int>>()
            for (cell in pad.cells) {
                for (row in cell.row until cell.row + cell.rowSpan) {
                    assertTrue("overlap at $row,${cell.column}", occupied.add(row to cell.column))
                    assertTrue(row in 0 until EditorDigitLayouts.ROWS)
                }
                assertTrue(cell.column in 0 until EditorDigitLayouts.COLUMNS)
            }
        }
    }

    @Test
    fun anIntegerFieldOffersNeitherASignNorAPointAndDeleteTakesTwoRows() {
        val pad = pad(NumericEditorKind.NUMBER)
        assertFalse(pad.labels().contains("-"))
        assertFalse(pad.labels().contains("."))
        assertEquals(2, pad.at(0, 3)!!.rowSpan)
        // Number and verification-code fields share one bottom row: a lone 0.
        assertEquals(listOf("0"), pad.cells.filter { it.row == 3 && it.column < 3 }.map { it.key.label })
    }

    @Test
    fun theRightColumnTakesThePointOtherwiseTheSign() {
        val decimal = pad(NumericEditorKind.NUMBER, NumberInputFlags(decimal = true))
        assertEquals(".", decimal.at(1, 3)!!.key.label)
        val signed = pad(NumericEditorKind.NUMBER, NumberInputFlags(signed = true))
        assertEquals("-", signed.at(1, 3)!!.key.label)
        val both = pad(NumericEditorKind.NUMBER, NumberInputFlags(signed = true, decimal = true))
        assertEquals(".", both.at(1, 3)!!.key.label)
        assertEquals("the sign moves next to the 0", "-", both.at(3, 0)!!.key.label)
    }

    @Test
    fun aPhoneFieldCarriesPlusStarAndHash() {
        val phone = pad(NumericEditorKind.PHONE)
        assertEquals("+", phone.at(1, 3)!!.key.label)
        assertEquals("*", phone.at(3, 0)!!.key.label)
        assertEquals("#", phone.at(3, 2)!!.key.label)
        assertFalse(phone.labels().contains("."))
    }

    @Test
    fun aPhoneFieldsFirstPageEndsInTheSymbolsKeyAndTheActionKey() {
        val phone = pad(NumericEditorKind.PHONE)
        assertEquals(
            LayoutActionKind.PAGE_SWITCH,
            phone
                .at(2, 3)!!
                .key.action.type,
        )
        assertEquals(
            "symbols",
            phone
                .at(2, 3)!!
                .key.action.value,
        )
        assertEquals(
            LayoutActionKind.ENTER,
            phone
                .at(3, 3)!!
                .key.action.type,
        )
        assertEquals(1, phone.at(3, 3)!!.rowSpan)
    }

    @Test
    fun aPhoneFieldsSecondPageOffersTheDialersOtherCharactersAndALeadBackToTheDigits() {
        val symbols = requireNotNull(EditorDigitLayouts.pad(NumericEditorKind.PHONE, phoneSymbols = true))
        val typed =
            symbols.cells.mapNotNull {
                it.key.action
                    .takeIf { a -> a.type == LayoutActionKind.LITERAL }
                    ?.value
            }
        // AOSP's phone symbol page plus the pause and wait characters of TYPE_CLASS_PHONE.
        for (char in listOf("(", ")", "/", "-", "N", ",", ";", ".", "*", "#", "+")) {
            assertEquals(char, 1, typed.count { it == char })
        }
        assertEquals(1, symbols.cells.count { it.key.action.type == LayoutActionKind.SPACE })
        assertEquals(1, symbols.cells.count { it.key.action.type == LayoutActionKind.BACKSPACE })
        assertEquals(1, symbols.cells.count { it.key.action.type == LayoutActionKind.ENTER })
        val back = symbols.cells.single { it.key.action.type == LayoutActionKind.PAGE_SWITCH }
        assertEquals("numeric", back.key.action.value)
        val occupied = HashSet<Pair<Int, Int>>()
        for (cell in symbols.cells) {
            for (column in cell.column until cell.column + cell.columnSpan) {
                assertTrue("overlap at ${cell.row},$column", occupied.add(cell.row to column))
            }
        }
        assertEquals("the page fills the grid", 16, occupied.size)
        val shell = EditorDigitLayouts.build(symbols, 400f, 224f)
        assertEquals(400f, shell.keys.maxOf { it.right }, 0.01f)
    }

    @Test
    fun onlyAPhoneFieldHasASecondPage() {
        val number = EditorDigitLayouts.pad(NumericEditorKind.NUMBER, phoneSymbols = true)
        assertTrue(number === EditorDigitLayouts.pad(NumericEditorKind.NUMBER))
    }

    @Test
    fun aDateTimeFieldWithoutAVariationCarriesOneSeparatorOfEach() {
        val both = pad(NumericEditorKind.DATETIME, dateTime = DateTimeFlags(date = true, time = true))
        for (symbol in listOf(":", "/", "-")) assertEquals(symbol, 1, both.labels().count { it == symbol })
        assertFalse(both.labels().contains("."))
    }

    @Test
    fun aDateFieldCarriesOnlyDateSeparators() {
        // Platform DateKeyListener accepts digits, '/', '.' and '-'; a colon is not one of them.
        val date = pad(NumericEditorKind.DATETIME, dateTime = DateTimeFlags(date = true)).labels()
        for (symbol in listOf("/", "-", ".")) assertEquals(symbol, 1, date.count { it == symbol })
        assertFalse("a date field rejects a colon", date.contains(":"))
    }

    @Test
    fun aTimeFieldCarriesTheColonAndNothingTheFieldRejects() {
        val time = pad(NumericEditorKind.DATETIME, dateTime = DateTimeFlags(time = true))
        assertEquals("exactly one colon", 1, time.labels().count { it == ":" })
        // Platform TimeKeyListener accepts digits, ':', and the AM/PM letters only.
        for (rejected in listOf("-", "/", ".")) assertFalse(rejected, time.labels().contains(rejected))
    }

    @Test
    fun digitsAndSymbolsTypeThemselves() {
        for (pad in allPads()) {
            for (cell in pad.cells) {
                if (cell.key.action.type == LayoutActionKind.LITERAL) {
                    assertEquals(cell.key.label, cell.key.action.value)
                }
            }
        }
    }

    @Test
    fun theSamePadForTheSameEditorIsTheSameInstance() {
        assertTrue(
            pad(NumericEditorKind.NUMBER, NumberInputFlags(decimal = true)) ===
                pad(NumericEditorKind.NUMBER, NumberInputFlags(decimal = true)),
        )
    }

    @Test
    fun geometryTilesTheKeyAreaInFourBandsAndFourColumns() {
        val width = 400f
        val height = 224f
        val pad = pad(NumericEditorKind.NUMBER, NumberInputFlags(signed = true, decimal = true))
        val shell = EditorDigitLayouts.build(pad, width, height)
        assertEquals(pad.cells.size, shell.keys.size)
        val eight = shell.keys.first { it.key.label == "8" }
        val zero = shell.keys.first { it.key.label == "0" }
        assertEquals(eight.left, zero.left, 0.001f)
        assertEquals(eight.bottom, zero.top, 0.001f)
        assertEquals(height, shell.keys.maxOf { it.bottom }, 0.001f)
        assertEquals(width, shell.keys.maxOf { it.right }, 0.01f)
        val action = shell.keys.first { it.key.action.type == LayoutActionKind.ENTER }
        assertEquals(2 * (height / 4), action.bottom - action.top, 0.001f)
    }
}
