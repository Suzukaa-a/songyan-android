package app.songyan.ime.ime

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutSplitConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSplitTest {
    private fun letter(label: String) = LayoutKey(label, action = LayoutAction.code(label))

    private fun row(text: String) = text.map { letter(it.toString()) }

    private val space = LayoutKey("空格", width = 4.0, action = LayoutAction(LayoutActionKind.SPACE))

    @Test
    fun aSplitRowKeepsEveryKeyAndOpensTheGutterAtTheCut() {
        val rows = listOf(row("abcdefghij"))
        val plain = KeyRowSolver.slots(rows, 0f, 0f, 50f, 1000f, 4f)[0]
        val split = KeyRowSolver.slots(rows, 0f, 0f, 50f, 1000f, 4f, KeyRowSolver.RowSplit(200f, listOf(5)))[0]
        assertEquals(10, split.size)
        // The gutter lies between the fifth and sixth key, on top of the ordinary gap.
        assertEquals(204f, split[5].left - split[4].right, 0.01f)
        // The row still ends where it ended: the keys shrank to pay for the gutter.
        assertEquals(plain.last().right, split.last().right, 0.01f)
        assertTrue(split[0].right - split[0].left < plain[0].right - plain[0].left)
    }

    @Test
    fun allRowsShareOneGutterAndOneKeyWidth() {
        val rows = listOf(row("abcdefghij"), row("klmnopqrs"), row("tuvwxyz"))
        val split =
            KeyRowSolver.slots(rows, 0f, 0f, 50f, 1000f, 4f, KeyRowSolver.RowSplit(160f, listOf(5, 5, 4)))
        val widths = split.map { it[0].right - it[0].left }
        assertEquals(widths[0], widths[1], 0.01f)
        assertEquals(widths[0], widths[2], 0.01f)
        // The shortest row is centred as a whole, gutter included.
        assertEquals(split[2].first().left - 0f, 1000f - split[2].last().right, 0.5f)
    }

    @Test
    fun aRowCutOutsideItsKeysStaysWhole() {
        val rows = listOf(row("abc"))
        val whole = KeyRowSolver.slots(rows, 0f, 0f, 50f, 300f, 4f, KeyRowSolver.RowSplit(80f, listOf(0)))[0]
        val plain = KeyRowSolver.slots(rows, 0f, 0f, 50f, 300f, 4f)[0]
        assertEquals(plain.map { it.left to it.right }.size, whole.size)
        // The gutter is still room taken from every row's keys, but no gap opens inside this row.
        assertEquals(4f, whole[1].left - whole[0].right, 0.01f)
    }

    @Test
    fun theFooterSpaceBecomesTwoHalvesUnderEachThumb() {
        val columns = listOf(row("abcd"), listOf(letter("1"), space, letter("2")))
        val (cut, breaks) = splitColumns(columns, LayoutSplitConfiguration.of(rowBreaks = listOf(2, 0)))
        assertEquals(listOf(2, 2), breaks)
        assertEquals(4, cut[1].size)
        assertEquals(LayoutActionKind.SPACE, cut[1][1].action.type)
        assertEquals(LayoutActionKind.SPACE, cut[1][2].action.type)
        assertEquals(2.0, cut[1][1].widthWeight, 1e-9)
        // The split falls between the halves.
        assertEquals(2, breaks[1])
    }

    @Test
    fun theMiddleCutIsTheDefaultForARowWithoutAStoredCut() {
        val (_, breaks) = splitColumns(listOf(row("abcdefghij"), row("abcdefg")), LayoutSplitConfiguration())
        assertEquals(listOf(5, 4), breaks)
    }

    @Test
    fun aSplitLeavesAMarginAtBothEdgesAndWidensTheKeysOverTheSharedGutter() {
        val split = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        for (width in listOf(700.0, 860.0, 1024.0, 1366.0)) {
            val plan = SplitFrame.plan(split, width, keyCount = 10, spacing = 4.0)
            assertTrue("$width", plan.inset >= 8.0)
            assertTrue("$width", plan.gutter < split.gap(width, 10, 4.0))
            val half = (width - 2 * plan.inset - plan.gutter) / 2
            assertTrue("$width", half <= SplitFrame.MAXIMUM_HALF_DP + 1e-9)
        }
    }

    @Test
    fun aNarrowSplitGivesUpTheMarginBeforeTheKeys() {
        val split = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        val plan = SplitFrame.plan(split, 400.0, keyCount = 10, spacing = 4.0)
        val key = (400.0 - 2 * plan.inset - plan.gutter - 9 * 4.0) / 10
        assertTrue("$key", key >= LayoutSplitConfiguration.MINIMUM_AUTOMATIC_KEY_WIDTH - 1e-9)
        assertTrue(plan.gutter > 0.0)
    }

    /** The letters left of the cut in each letter row of the real page, Shift and Delete left out. */
    private fun leftLetters(
        layout: KeyboardLayoutKind,
        field: FieldKind = FieldKind.TEXT,
    ): List<String> {
        val config =
            KeyboardPages.Config(
                layout = layout,
                englishDirectInput = false,
                symbolMarksAscii = false,
                languageSwitch = true,
                lettersReachable = true,
                field = field,
            )
        val page = KeyboardPages.definition(null, config)
        val split = WideSurface.effectiveSplit(page.split, SplitMode.ON)!!
        val (cut, breaks) = splitColumns(page.rows.map { it.keys }, split)
        return cut.indices
            .filter { row -> cut[row].any { it.action.type == LayoutActionKind.CODE } }
            .map { row ->
                cut[row]
                    .take(breaks[row])
                    .filter { it.action.type == LayoutActionKind.CODE }
                    .joinToString("") { it.label.lowercase() }
            }
    }

    @Test
    fun twentySixKeysPutFiveLettersOfEveryRowOnTheLeft() {
        assertEquals(listOf("qwert", "asdfg", "zxcvb"), leftLetters(KeyboardLayoutKind.QWERTY26))
        // A password field's digit row on top does not move the letters.
        assertEquals(listOf("qwert", "asdfg", "zxcvb"), leftLetters(KeyboardLayoutKind.QWERTY26, FieldKind.PASSWORD))
    }

    @Test
    fun eighteenKeysPutThreeThreeAndFourKeysOnTheLeft() {
        assertEquals(listOf("qwert", "asdfg", "zxcvbn"), leftLetters(KeyboardLayoutKind.MERGED18))
    }
}
