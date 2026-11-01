package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitArrangementTest {
    private fun letter(c: Char) = LayoutKey(c.toString(), action = LayoutAction.code(c.toString()))

    /** The acceptance layout: qwerty26 whose split arrangement repeats y, g and v on both halves. */
    private fun duplicated(): LayoutDefinition {
        val base = BuiltinLayouts.qwerty26
        val split = LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.ALWAYS)
        val derived = split.derivedArrangement(base.rows).toMutableList()
        derived[0] = derived[0].copy(left = derived[0].left + letter('y'))
        derived[1] = derived[1].copy(right = listOf(letter('g')) + derived[1].right)
        derived[2] = derived[2].copy(right = listOf(letter('v')) + derived[2].right)
        return base.withSplit(split.copy(halves = derived))
    }

    private fun text(keys: List<LayoutKey>) = keys.joinToString("") { it.label }

    @Test
    fun theWholeArrangementIsUnchangedAndTheHalvesCarryTheDuplicates() {
        val layout = duplicated()
        assertEquals(BuiltinLayouts.qwerty26.rows, layout.rows)
        val halves = layout.split!!.halves!!
        assertEquals(listOf("qwerty", "yuiop"), listOf(text(halves[0].left), text(halves[0].right)))
        assertEquals(listOf("asdfg", "ghjkl"), listOf(text(halves[1].left), text(halves[1].right)))
        assertEquals(listOf("zxcv", "vbnm"), listOf(text(halves[2].left), text(halves[2].right)))
        for (letter in listOf('y', 'g', 'v')) {
            val sent = halves.flatMap { it.keys }.filter { it.label == letter.toString() }
            assertEquals(2, sent.size)
            assertTrue(sent.all { it.action == LayoutAction.code(letter.toString()) })
        }
    }

    @Test
    fun theHalvesRoundTripThroughJsonAndAreWrittenOnlyWhenSet() {
        val layout = duplicated()
        val text = layout.encoded()
        assertTrue(text.contains("\"halves\""))
        assertEquals(layout, LayoutJson.decode(text))
        assertFalse(
            BuiltinLayouts.qwerty26
                .withSplit(LayoutSplitConfiguration.of())
                .encoded()
                .contains("halves"),
        )
    }

    @Test
    fun withoutHalvesTheArrangementIsTheRowsCutAtTheirBreaks() {
        val split = LayoutSplitConfiguration.of(rowBreaks = listOf(4, 5, 3, 1))
        val arranged = split.arrangement(BuiltinLayouts.qwerty26.rows)
        assertEquals(listOf("qwer", "tyuiop"), listOf(text(arranged[0].left), text(arranged[0].right)))
        // The control row's space bar becomes one half under each thumb.
        val control = arranged.last()
        assertTrue(
            control.left
                .last()
                .action.type == LayoutActionKind.SPACE,
        )
        assertTrue(
            control.right
                .first()
                .action.type == LayoutActionKind.SPACE,
        )
    }

    @Test
    fun aLayoutWithoutAGapSettingDecodesWithTheAutomaticGap() {
        val text =
            BuiltinLayouts.qwerty26
                .encoded()
                .replaceFirst("{", "{\n  \"split\": { \"activation\": \"always\", \"rowBreaks\": [] },")
        val split = LayoutJson.decode(text).split!!
        assertTrue(split.automaticGap)
        assertEquals(LayoutSplitConfiguration.DEFAULT_GAP_FRACTION, split.gapFraction, 0.0)
        assertTrue(LayoutSplitConfiguration().automaticGap)
        assertTrue(split.gap(1000.0, 10) > 0.0)
    }

    private fun invalid(
        code: LayoutInvalidCode,
        layout: LayoutDefinition,
    ) {
        try {
            layout.validate()
            fail("expected " + code)
        } catch (error: LayoutException.Invalid) {
            assertEquals(code, error.code)
        }
    }

    @Test
    fun theHalvesMustMatchTheRowsAndKeepAKeyInEachHalf() {
        val layout = duplicated()
        val split = layout.split!!
        invalid(
            LayoutInvalidCode.SPLIT_HALVES_ROW_COUNT_MISMATCH,
            layout.withSplit(split.copy(halves = split.halves!!.dropLast(1))),
        )
        val emptied = split.halves!!.toMutableList().also { it[0] = it[0].copy(right = emptyList()) }
        invalid(LayoutInvalidCode.SPLIT_HALF_EMPTY, layout.withSplit(split.copy(halves = emptied)))
        // A key inside a half is validated like any other key.
        val broken =
            split.halves!!.toMutableList().also {
                it[0] =
                    it[0].copy(left = listOf(letter('y').copy(label = "")))
            }
        invalid(
            LayoutInvalidCode.KEY_REQUIRES_LABEL_AND_POSITIVE_FINITE_WIDTH,
            layout.withSplit(split.copy(halves = broken)),
        )
    }

    @Test
    fun breaksAreNotCheckedAgainstTheWholeRowsWhenHalvesExist() {
        val layout = duplicated()
        // A stale break that would be out of range for a whole row is ignored once halves exist.
        layout.withSplit(layout.split!!.copy(rowBreaks = listOf(99, 99, 99, 99))).validate()
    }

    @Test
    fun aPageDoesNotCarryItsOwnHalves() {
        val page = KeyboardPagePresets.englishQwerty.withSplit(duplicated().split)
        try {
            page.validate()
            fail("expected the page to be rejected")
        } catch (error: LayoutException.Invalid) {
            assertEquals(LayoutInvalidCode.SPLIT_HALVES_ONLY_ON_LAYOUT, error.code)
        }
    }

    @Test
    fun pagesInheritingTheSplitDropTheHalves() {
        val inherited = LayoutSplitConfiguration.resolving(null, duplicated().split)!!
        assertNull(inherited.halves)
        assertEquals(emptyList<Int>(), inherited.rowBreaks)
    }

    @Test
    fun keysOnlyTheHalvesSendStillCountAsSendingCodes() {
        val base = BuiltinLayouts.qwerty26
        val literal = LayoutKey("，", action = LayoutAction.literal("，"))
        val row = LayoutRow(listOf(literal, literal))
        val only =
            LayoutDefinition(
                id = "x",
                name = "x",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows = listOf(row),
                split =
                    LayoutSplitConfiguration(
                        halves = listOf(LayoutSplitRow(listOf(letter('a')), listOf(letter('b')))),
                    ),
            )
        assertTrue(only.sendsEngineCode)
        assertTrue(base.sendsEngineCode)
    }
}
