package app.songyan.ime.keyboard.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from KeyboardCoreTests.T9PunctuationCycleTests. */
class T9PunctuationCycleTest {
    private val symbols = listOf("，", "。", "？", "！", "、", "；", "：", "……")

    @Test
    fun repeatedTapsReplaceAndWrapIncludingFullEllipsis() {
        val cycle = T9PunctuationCycle()
        var context = "正文"
        for (index in 0..symbols.size) {
            val edit = requireNotNull(cycle.next(symbols, context, index * 0.1, index))
            assertEquals(symbols[index % symbols.size], edit.text)
            if (index > 0) {
                assertEquals(symbols[(index - 1) % symbols.size], edit.replacing)
                context = context.dropLast(requireNotNull(edit.replacing).length)
            } else {
                assertNull(edit.replacing)
            }
            context += edit.text
            cycle.record(edit, symbols, context, index * 0.1, index + 1)
        }
        assertEquals("正文，", context)
    }

    @Test
    fun timeoutCursorChangeUnknownContextAndOtherCommitStartFresh() {
        val cycle = T9PunctuationCycle()
        val edit = requireNotNull(cycle.next(symbols, "正文", 1.0, 0))
        cycle.record(edit, symbols, "正文，", 1.0, 1)
        val cases =
            listOf(
                Triple<String?, Double, Int>("正文，", 1.8, 1),
                Triple<String?, Double, Int>("其他，", 1.1, 1),
                Triple<String?, Double, Int>(null, 1.1, 1),
                Triple<String?, Double, Int>("正文，", 1.1, 2),
                Triple<String?, Double, Int>("正文，", 0.9, 1),
            )
        for ((context, now, revision) in cases) {
            val fresh = requireNotNull(cycle.next(symbols, context, now, revision))
            assertEquals("，", fresh.text)
            assertNull(fresh.replacing)
        }
    }

    @Test
    fun widthChangeResetAndFailedInsertNeverReplace() {
        val cycle = T9PunctuationCycle()
        val edit = requireNotNull(cycle.next(symbols, "", 0.0, 0))
        cycle.record(edit, symbols, "，", 0.0, 1)
        assertNull(cycle.next(listOf(",", ".", "?"), "，", 0.1, 1)?.replacing)
        cycle.reset()
        assertNull(cycle.next(symbols, "，", 0.1, 1)?.replacing)
        cycle.record(edit, symbols, "not inserted", 0.0, 1)
        assertNull(cycle.next(symbols, "，", 0.1, 1)?.replacing)
        assertNull(cycle.next(emptyList(), "", 0.0, 0))
    }

    @Test
    fun unchangedOrUnverifiedHostReceiptNeverArmsReplacement() {
        for (before in listOf<String?>("正文，", null, "，".repeat(200))) {
            val cycle = T9PunctuationCycle()
            val edit = requireNotNull(cycle.next(symbols, before, 0.0, 0))
            val after = before ?: "原有，"
            cycle.record(edit, symbols, after, 0.0, 1)
            assertNull(cycle.next(symbols, after, 0.1, 1)?.replacing)
        }
    }

    @Test
    fun contextIsBoundedWithoutSplittingEmoji() {
        val cycle = T9PunctuationCycle()
        val text = "👩🏽‍💻".repeat(200) + "，"
        val edit = requireNotNull(cycle.next(symbols, text.dropLast(1), 0.0, 0))
        cycle.record(edit, symbols, text, 0.0, 1)
        val next = requireNotNull(cycle.next(symbols, text, 0.1, 1))
        // The limit counts user-perceived characters (as on iOS): 127 whole emoji and the comma, none cut.
        assertEquals("👩🏽‍💻".repeat(T9PunctuationCycle.CONTEXT_LIMIT - 1) + "，", next.expectedContext)
        assertEquals("，", next.replacing)
    }

    /** The context read right after a keyboard write is often the stale pre-write value. */
    @Test
    fun staleReceiptArmsWhenNextTapSeesTheWrite() {
        val cycle = T9PunctuationCycle()
        val edit = requireNotNull(cycle.next(symbols, "正文", 0.0, 0))
        cycle.record(edit, symbols, "正文", 0.0, 1)
        val next = requireNotNull(cycle.next(symbols, "正文，", 0.3, 1))
        assertEquals("，", next.replacing)
        assertEquals("。", next.text)
        cycle.record(next, symbols, "正文，", 0.3, 2)
        val third = requireNotNull(cycle.next(symbols, "正文。", 0.6, 2))
        assertEquals("。", third.replacing)
        assertEquals("？", third.text)
    }

    /** An empty field: the first tap on the first key can start cycling. */
    @Test
    fun emptyFieldArmsOnTheFirstTap() {
        val cycle = T9PunctuationCycle()
        val edit = requireNotNull(cycle.next(symbols, "", 0.0, 0))
        cycle.record(edit, symbols, "", 0.0, 1)
        val next = requireNotNull(cycle.next(symbols, "，", 0.3, 1))
        assertEquals("，", next.replacing)
        assertEquals("。", next.text)
    }
}
